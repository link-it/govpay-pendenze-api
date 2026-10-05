package it.govpay.pendenze.ricevuta;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtMapEntry;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtMetadata;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceipt;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtSubject;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAReceiptV2;
import it.govpay.pendenze.api.model.RTMetadataEntry;
import it.govpay.pendenze.api.model.RTSubject;
import it.govpay.pendenze.api.model.RTSubjectUniqueIdentifier;
import it.govpay.pendenze.api.model.RTTransfer;
import it.govpay.pendenze.api.model.RTTransferV2;
import it.govpay.pendenze.api.model.RicevutaCtReceipt;
import it.govpay.pendenze.api.model.RicevutaCtReceiptV2;
import it.govpay.pendenze.api.model.TipoRicevuta;
import it.govpay.pendenze.api.model.TipoSoggetto;

/**
 * Mapping {@link CtReceipt}/{@link CtReceiptV2} (bean JAXB, formati Nodo dei Pagamenti
 * {@code ctReceipt}/{@code ctReceiptV2}) → {@link RicevutaCtReceipt}/{@link RicevutaCtReceiptV2}
 * (DTO OpenAPI v3). I due formati condividono quasi tutti i campi (vedi XSD {@code paForNode.xsd},
 * {@code RTReceiptBase} nello YAML), ma {@code openapi-generator} non genera una classe base
 * comune per schemi {@code allOf} appiattiti: i due metodi {@link #mapCtReceipt}/
 * {@link #mapCtReceiptV2} duplicano quindi l'assegnazione dei campi comuni, non essendoci
 * un'interfaccia di setter condivisa su cui fattorizzarla.
 */
final class RicevutaCtReceiptMapper {

    private RicevutaCtReceiptMapper() {
    }

    static RicevutaCtReceipt mapCtReceipt(CtReceipt receipt) {
        RicevutaCtReceipt dto = new RicevutaCtReceipt();
        dto.setTipo(TipoRicevuta.CT_RECEIPT);
        dto.setReceiptId(receipt.getReceiptId());
        dto.setNoticeNumber(receipt.getNoticeNumber());
        dto.setFiscalCode(receipt.getFiscalCode());
        dto.setOutcome(RicevutaCtReceipt.OutcomeEnum.fromValue(receipt.getOutcome().value()));
        dto.setCreditorReferenceId(receipt.getCreditorReferenceId());
        dto.setPaymentAmount(receipt.getPaymentAmount());
        dto.setDescription(receipt.getDescription());
        dto.setCompanyName(receipt.getCompanyName());
        dto.setOfficeName(receipt.getOfficeName());
        dto.setDebtor(mapSubject(receipt.getDebtor()));
        dto.setIdPSP(receipt.getIdPSP());
        dto.setPspFiscalCode(receipt.getPspFiscalCode());
        dto.setPspPartitaIVA(receipt.getPspPartitaIVA());
        dto.setPsPCompanyName(receipt.getPSPCompanyName());
        dto.setIdChannel(receipt.getIdChannel());
        dto.setChannelDescription(receipt.getChannelDescription());
        if (receipt.getPayer() != null) {
            dto.setPayer(mapSubject(receipt.getPayer()));
        }
        dto.setPaymentMethod(receipt.getPaymentMethod());
        dto.setFee(receipt.getFee());
        dto.setPaymentDateTime(RTDateTimeUtils.toOffsetDateTimeConvenzionale(receipt.getPaymentDateTime()));
        dto.setApplicationDate(receipt.getApplicationDate());
        dto.setTransferDate(receipt.getTransferDate());
        dto.setMetadata(mapMetadata(receipt.getMetadata()));
        dto.setStandIn(receipt.isStandIn());
        if (receipt.getTransferList() != null) {
            dto.setTransferList(receipt.getTransferList().getTransfers().stream()
                    .map(RicevutaCtReceiptMapper::mapTransfer).toList());
        }
        return dto;
    }

    static RicevutaCtReceiptV2 mapCtReceiptV2(CtReceiptV2 receipt) {
        RicevutaCtReceiptV2 dto = new RicevutaCtReceiptV2();
        dto.setTipo(TipoRicevuta.CT_RECEIPT_V2);
        dto.setReceiptId(receipt.getReceiptId());
        dto.setNoticeNumber(receipt.getNoticeNumber());
        dto.setFiscalCode(receipt.getFiscalCode());
        dto.setOutcome(RicevutaCtReceiptV2.OutcomeEnum.fromValue(receipt.getOutcome().value()));
        dto.setCreditorReferenceId(receipt.getCreditorReferenceId());
        dto.setPaymentAmount(receipt.getPaymentAmount());
        dto.setDescription(receipt.getDescription());
        dto.setCompanyName(receipt.getCompanyName());
        dto.setOfficeName(receipt.getOfficeName());
        dto.setDebtor(mapSubject(receipt.getDebtor()));
        dto.setIdPSP(receipt.getIdPSP());
        dto.setPspFiscalCode(receipt.getPspFiscalCode());
        dto.setPspPartitaIVA(receipt.getPspPartitaIVA());
        dto.setPsPCompanyName(receipt.getPSPCompanyName());
        dto.setIdChannel(receipt.getIdChannel());
        dto.setChannelDescription(receipt.getChannelDescription());
        if (receipt.getPayer() != null) {
            dto.setPayer(mapSubject(receipt.getPayer()));
        }
        dto.setPaymentMethod(receipt.getPaymentMethod());
        dto.setPaymentNote(receipt.getPaymentNote());
        dto.setFee(receipt.getFee());
        dto.setPrimaryCiIncurredFee(receipt.getPrimaryCiIncurredFee());
        dto.setIdBundle(receipt.getIdBundle());
        dto.setIdCiBundle(receipt.getIdCiBundle());
        dto.setPaymentDateTime(RTDateTimeUtils.toOffsetDateTimeConvenzionale(receipt.getPaymentDateTime()));
        dto.setApplicationDate(receipt.getApplicationDate());
        dto.setTransferDate(receipt.getTransferDate());
        dto.setMetadata(mapMetadata(receipt.getMetadata()));
        dto.setStandIn(receipt.isStandIn());
        if (receipt.getTransferList() != null) {
            dto.setTransferList(receipt.getTransferList().getTransfers().stream()
                    .map(RicevutaCtReceiptMapper::mapTransferV2).toList());
        }
        return dto;
    }

    private static RTSubject mapSubject(CtSubject subject) {
        RTSubjectUniqueIdentifier identifier = new RTSubjectUniqueIdentifier();
        identifier.setEntityUniqueIdentifierType(
                TipoSoggetto.fromValue(subject.getUniqueIdentifier().getEntityUniqueIdentifierType().value()));
        identifier.setEntityUniqueIdentifierValue(subject.getUniqueIdentifier().getEntityUniqueIdentifierValue());

        RTSubject dto = new RTSubject();
        dto.setUniqueIdentifier(identifier);
        dto.setFullName(subject.getFullName());
        dto.setStreetName(subject.getStreetName());
        dto.setCivicNumber(subject.getCivicNumber());
        dto.setPostalCode(subject.getPostalCode());
        dto.setCity(subject.getCity());
        dto.setStateProvinceRegion(subject.getStateProvinceRegion());
        dto.setCountry(subject.getCountry());
        dto.setEmail(subject.getEMail());
        return dto;
    }

    /** {@code null} se {@code metadata} e' assente — stesso comportamento di un elenco vuoto non previsto. */
    private static java.util.List<RTMetadataEntry> mapMetadata(CtMetadata metadata) {
        if (metadata == null || metadata.getMapEntries() == null) {
            return null;
        }
        return metadata.getMapEntries().stream().map(RicevutaCtReceiptMapper::mapMetadataEntry).toList();
    }

    private static RTMetadataEntry mapMetadataEntry(CtMapEntry entry) {
        RTMetadataEntry dto = new RTMetadataEntry();
        dto.setChiave(entry.getKey());
        dto.setValore(entry.getValue());
        return dto;
    }

    private static RTTransfer mapTransfer(CtTransferPA transfer) {
        RTTransfer dto = new RTTransfer();
        dto.setIdTransfer(String.valueOf(transfer.getIdTransfer()));
        dto.setTransferAmount(transfer.getTransferAmount());
        dto.setFiscalCodePA(transfer.getFiscalCodePA());
        dto.setIBAN(transfer.getIBAN());
        dto.setRemittanceInformation(transfer.getRemittanceInformation());
        dto.setTransferCategory(transfer.getTransferCategory());
        dto.setMetadata(mapMetadata(transfer.getMetadata()));
        return dto;
    }

    private static RTTransferV2 mapTransferV2(CtTransferPAReceiptV2 transfer) {
        RTTransferV2 dto = new RTTransferV2();
        dto.setIdTransfer(String.valueOf(transfer.getIdTransfer()));
        dto.setTransferAmount(transfer.getTransferAmount());
        dto.setFiscalCodePA(transfer.getFiscalCodePA());
        dto.setCompanyName(transfer.getCompanyName());
        dto.setIBAN(transfer.getIBAN());
        dto.setMbDAttachment(transfer.getMBDAttachment());
        dto.setRemittanceInformation(transfer.getRemittanceInformation());
        dto.setTransferCategory(transfer.getTransferCategory());
        dto.setMetadata(mapMetadata(transfer.getMetadata()));
        return dto;
    }
}
