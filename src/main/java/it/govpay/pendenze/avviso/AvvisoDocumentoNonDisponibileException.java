package it.govpay.pendenze.avviso;

/**
 * L'avviso cumulativo di un documento non si puo' generare per una ragione diversa da MBT o
 * anagrafica debitore assente: nessuna rata pagabile (stesso caso dell'
 * {@code UnprocessableEntityException} legacy in {@code AvvisoPagamento.getBytesAvvisoDocumento}),
 * oppure richiesta bilingue non soddisfacibile. Mappata a 422 problem+json, stesso trattamento
 * di {@link AvvisoMbtException}.
 */
public class AvvisoDocumentoNonDisponibileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AvvisoDocumentoNonDisponibileException(String messaggio) {
        super(messaggio);
    }
}
