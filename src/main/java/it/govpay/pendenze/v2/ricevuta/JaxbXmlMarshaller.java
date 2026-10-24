package it.govpay.pendenze.v2.ricevuta;

import java.io.ByteArrayOutputStream;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

/**
 * Marshalling JAXB verso XML del contenuto {@code rpt}/{@code rt} per {@code GET
 * /rpp/{idDominio}/{iuv}/{ccp}/rpt}/{@code /rt} dello YAML v2, quando il client richiede
 * {@code application/xml} (vedi {@link RppContenutoMapper#toRptXml}/{@link
 * RppContenutoMapper#toRtXml}) — unica direzione di scrittura JAXB nel progetto:
 * {@code it.govpay.pendenze.ricevuta.pagopa.JaxbUtils} (govpay-common-pendenze) e'
 * deliberatamente di sola lettura (nessun consumatore v3-nativo ricostruisce XML pagoPA in
 * uscita), quindi questa capacita' resta scoped qui, al wrapper.
 *
 * <p>Due soli contesti possibili, stessi package di {@code JaxbUtils}: i bean nativi
 * ({@code PaGetPaymentRes}/{@code PaGetPaymentV2Response}, package {@code pafornode}) e i bean
 * convertiti in formato V1 ({@code RPT}/{@code RT}, package {@code digitpa.schemas._2011
 * .pagamenti}) — entrambi annotati {@code @XmlRootElement}, marshalling diretto senza bisogno
 * di incapsulare in un {@code JAXBElement}.</p>
 */
final class JaxbXmlMarshaller {

    private static final JAXBContext RPT_RT_CONTEXT = newContext("it.gov.digitpa.schemas._2011.pagamenti");
    private static final JAXBContext PA_FOR_NODE_CONTEXT = newContext("it.gov.pagopa.pagopa_api.pa.pafornode");

    private JaxbXmlMarshaller() {
    }

    private static JAXBContext newContext(String packageName) {
        try {
            return JAXBContext.newInstance(packageName);
        } catch (JAXBException e) {
            throw new IllegalStateException(
                    "Impossibile inizializzare il contesto JAXB per il package " + packageName, e);
        }
    }

    static byte[] toByte(Object jaxbBean) throws JAXBException {
        JAXBContext context = jaxbBean.getClass().getPackageName().startsWith("it.gov.pagopa.pagopa_api")
                ? PA_FOR_NODE_CONTEXT : RPT_RT_CONTEXT;
        Marshaller marshaller = context.createMarshaller();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        marshaller.marshal(jaxbBean, out);
        return out.toByteArray();
    }
}
