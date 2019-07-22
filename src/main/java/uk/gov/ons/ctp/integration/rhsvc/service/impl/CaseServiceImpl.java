package uk.gov.ons.ctp.integration.rhsvc.service.impl;

import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.glasnost.orika.MapperFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.gov.ons.ctp.common.error.CTPException;
import uk.gov.ons.ctp.common.error.CTPException.Fault;
import uk.gov.ons.ctp.common.event.EventPublisher;
import uk.gov.ons.ctp.common.event.EventPublisher.Channel;
import uk.gov.ons.ctp.common.event.EventPublisher.EventType;
import uk.gov.ons.ctp.common.event.EventPublisher.Source;
import uk.gov.ons.ctp.common.event.model.CollectionCase;
import uk.gov.ons.ctp.common.event.model.Contact;
import uk.gov.ons.ctp.common.event.model.FulfilmentRequest;
import uk.gov.ons.ctp.integration.common.product.ProductReference;
import uk.gov.ons.ctp.integration.common.product.model.Product;
import uk.gov.ons.ctp.integration.common.product.model.Product.CaseType;
import uk.gov.ons.ctp.integration.common.product.model.Product.DeliveryChannel;
import uk.gov.ons.ctp.integration.common.product.model.Product.Region;
import uk.gov.ons.ctp.integration.rhsvc.repository.RespondentDataRepository;
import uk.gov.ons.ctp.integration.rhsvc.representation.CaseDTO;
import uk.gov.ons.ctp.integration.rhsvc.representation.SMSFulfilmentRequestDTO;
import uk.gov.ons.ctp.integration.rhsvc.representation.UniquePropertyReferenceNumber;
import uk.gov.ons.ctp.integration.rhsvc.service.CaseService;

/** Implementation to deal with Case data */
@Service
public class CaseServiceImpl implements CaseService {

  private static final Logger log = LoggerFactory.getLogger(CaseServiceImpl.class);

  @Autowired private RespondentDataRepository dataRepo;
  @Autowired private MapperFacade mapperFacade;
  @Autowired private ProductReference productReference;

  @Autowired private EventPublisher publisher;

  @Override
  public List<CaseDTO> getHHCaseByUPRN(final UniquePropertyReferenceNumber uprn)
      throws CTPException {

    String uprnValue = Long.toString(uprn.getValue());
    log.debug("Fetching case details by UPRN: {}", uprnValue);

    List<CollectionCase> rmCase = dataRepo.readCollectionCasesByUprn(uprnValue);
    List<CollectionCase> results =
        rmCase
            .stream()
            .filter(c -> c.getAddress().getAddressType().equals(CaseType.HH.name()))
            .collect(Collectors.toList());
    List<CaseDTO> caseData = mapperFacade.mapAsList(results, CaseDTO.class);

    log.debug("{} HH case(s) retrieved for UPRN {}", caseData.size(), uprnValue);

    return caseData;
  }

  /**
   * This method contains the business logic for submitting a fulfilment by SMS request.
   *
   * @param requestBodyDTO contains the parameters from the originating http POST request.
   * @throws CTPException if the specified case cannot be found, or if no matching product is found.
   */
  @Override
  public void fulfilmentRequestBySMS(SMSFulfilmentRequestDTO requestBodyDTO) throws CTPException {
    UUID caseId = requestBodyDTO.getCaseId();

    // Read case from firestore
    Optional<CollectionCase> caseDetails = dataRepo.readCollectionCase(caseId.toString());
    if (caseDetails.isEmpty()) {
      String errorMessage = "Case not found: " + caseId;
      log.info(errorMessage);
      throw new CTPException(Fault.RESOURCE_NOT_FOUND, errorMessage);
    }

    // Attempt to find the requested product
    Product product =
        findProduct(caseDetails.get(), requestBodyDTO.getFulfilmentCode(), DeliveryChannel.SMS);
    if (product == null) {
      log.info("fulfilmentRequestBySMS can't find compatible product");
      throw new CTPException(Fault.BAD_REQUEST, "Compatible product cannot be found");
    }

    // Build and send a fulfilment request event
    FulfilmentRequest fulfilmentRequestedPayload =
        createFulfilmentRequestPayload(product, requestBodyDTO.getTelNo(), caseDetails.get());
    publisher.sendEvent(
        EventType.FULFILMENT_REQUESTED,
        Source.RESPONDENT_HOME,
        Channel.RH,
        fulfilmentRequestedPayload);
  }

  // Search the ProductReference for the specified product
  private Product findProduct(
      CollectionCase caseDetails, String fulfilmentCode, Product.DeliveryChannel deliveryChannel)
      throws CTPException {

    Region region = Region.valueOf(caseDetails.getAddress().getRegion());

    log.with("region", region)
        .with("deliveryChannel", deliveryChannel)
        .with("fulfilmentCode", fulfilmentCode)
        .debug("Attempting to find product.");

    // Build search criteria base on the cases details and the requested fulfilmentCode
    Product searchCriteria = new Product();
    searchCriteria.setRequestChannels(Arrays.asList(Product.RequestChannel.RH));
    searchCriteria.setRegions(Arrays.asList(region));
    searchCriteria.setDeliveryChannel(deliveryChannel);
    searchCriteria.setFulfilmentCode(fulfilmentCode);

    // Attempt to find matching product
    List<Product> products = productReference.searchProducts(searchCriteria);
    if (products.size() == 0) {
      return null;
    }

    return products.get(0);
  }

  private FulfilmentRequest createFulfilmentRequestPayload(
      Product product, String telephoneNumber, CollectionCase caseDetails) {
    // Create the event payload request
    FulfilmentRequest fulfilmentRequest = new FulfilmentRequest();
    fulfilmentRequest.setCaseId(caseDetails.getId());
    if (product.getCaseType().equals(Product.CaseType.HI)) {
      fulfilmentRequest.setIndividualCaseId(UUID.randomUUID().toString());
    }
    fulfilmentRequest.setFulfilmentCode(product.getFulfilmentCode());

    // Use the phone number that was supplied for this fulfilment request
    fulfilmentRequest.setContact(new Contact());
    fulfilmentRequest.getContact().setTelNo(telephoneNumber);

    return fulfilmentRequest;
  }
}
