package it.govpay.pendenze.avviso;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.repository.DominioLogoRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.pendenze.api.model.LinguaSecondaria;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.stampe.client.model.Amount;
import it.govpay.stampe.client.model.Creditor;
import it.govpay.stampe.client.model.Debtor;
import it.govpay.stampe.client.model.Iban;
import it.govpay.stampe.client.model.Languages;
import it.govpay.stampe.client.model.NoticeMetadataSecondLanguage;
import it.govpay.stampe.client.model.PaymentNotice;

/**
 * Mapping {@code Pendenza} → {@link PaymentNotice} per la generazione PDF via microservizio
 * {@code govpay-stampe}, per la variante {@code application/pdf} di
 * {@code GET /pendenze/{idA2A}/{idPendenza}/stampa}.
 */
@Component
public class AvvisoPdfPayloadMapper {

    /** Limite del contratto {@code govpay-stampe.yaml} su {@code Creditor.postal_auth_message}. */
    private static final int MAX_POSTAL_AUTH_MESSAGE = 70;

    /** Limite del contratto {@code govpay-stampe.yaml} su {@code Iban.owner_business_name}. */
    private static final int MAX_OWNER_BUSINESS_NAME = 50;

    /** Limite del contratto {@code govpay-stampe.yaml} su {@code Creditor.business_name}. */
    private static final int MAX_BUSINESS_NAME = 50;

    /** Limite del contratto {@code govpay-stampe.yaml} su {@code Debtor.full_name}. */
    private static final int MAX_FULL_NAME = 70;

    /**
     * Limite del contratto {@code govpay-stampe.yaml} su {@code Debtor.address_line_1}/
     * {@code address_line_2}.
     */
    private static final int MAX_ADDRESS_LINE = 70;

    private final DominioRepository dominioRepository;
    private final IbanAccreditoRepository ibanAccreditoRepository;
    private final TributoRepository tributoRepository;
    private final DominioLogoRepository dominioLogoRepository;

    public AvvisoPdfPayloadMapper(DominioRepository dominioRepository,
            IbanAccreditoRepository ibanAccreditoRepository, TributoRepository tributoRepository,
            DominioLogoRepository dominioLogoRepository) {
        this.dominioRepository = dominioRepository;
        this.ibanAccreditoRepository = ibanAccreditoRepository;
        this.tributoRepository = tributoRepository;
        this.dominioLogoRepository = dominioLogoRepository;
    }

    public PaymentNotice toPaymentNotice(Pendenza pendenza, LinguaSecondaria linguaSecondaria,
            String causaleTradotta, String informativaImporto, String informativaImportoTradotta) {
        DominioEntity dominio = dominioRepository.findById(pendenza.getIdDominio())
                .orElseThrow(() -> new IllegalStateException(
                        "idDominio [" + pendenza.getIdDominio() + "] della pendenza non risolve a nessun dominio "
                                + "censito: incoerenza del dato, il vincolo di integrita' referenziale dovrebbe "
                                + "impedirlo"));

        PaymentNotice notice = new PaymentNotice();
        notice.setLanguage(Languages.IT);
        notice.setCreditor(mapCreditor(dominio));
        notice.setDebtor(mapDebtor(primoSoggettoDebitore(pendenza)));
        // 'title' e' l'oggetto del pagamento (oggetto_del_pagamento/XSD), non un'intestazione
        // fissa: stesso campo che in console-api porta la causale vera, qui la descrizione
        // della posizione debitoria (stesso valore gia' usato per Avviso.descrizione nel ramo
        // JSON, vedi AvvisoMapper).
        notice.setTitle(descrizioneDi(pendenza));
        IbanAccreditoEntity postale = ibanPostale(pendenza);
        notice.setPostal(postale != null);
        notice.setFirstLogo(logoDi(dominio));
        notice.setFull(mapFullAmount(pendenza, dominio, postale));
        notice.setInformativaImporto(informativaImporto);
        Languages secondaria = toClientLanguage(linguaSecondaria);
        if (secondaria != null) {
            notice.setSecondLanguage(buildSecondLanguage(secondaria, causaleTradotta, informativaImportoTradotta));
        }
        return notice;
    }

    private static String descrizioneDi(Pendenza pendenza) {
        return pendenza.getOpzionePagamento().getPosizioneDebitoria().getDescrizione();
    }

    /**
     * {@link DominioLogoRepository} e' letta via {@code codDominio} (non l'id numerico): e'
     * l'unica chiave che la query JPQL di govpay-common espone, stessa entity "slim"
     * {@code DominioLogoEntity} gia' usata da govpay-portal-api per non caricare il BLOB su
     * ogni lettura di {@link DominioEntity}. Array vuoto se il dominio non ha un logo
     * caricato (colonna nulla) o se la query non trova alcuna riga — {@code govpay-stampe}
     * applica il proprio default quando riceve contenuto vuoto.
     */
    private byte[] logoDi(DominioEntity dominio) {
        byte[] logo = dominioLogoRepository.findLogoByCodDominio(dominio.getCodDominio()).orElse(null);
        return logo != null ? logo : new byte[0];
    }

    /**
     * La pendenza e' una Marca da Bollo Telematica se almeno una voce ha
     * {@code tipoBollo}/{@code hashDocumento}/{@code provinciaResidenza} tutti valorizzati —
     * porto di {@code VersamentoUtils.isPendenzaMBT} (v2), stessi nomi di colonna su
     * {@link VocePendenza}. L'avviso PDF non le si applica: il chiamante deve verificarlo
     * PRIMA di costruire il payload (422, non una 4xx generica sulla chiamata a govpay-stampe).
     */
    public static boolean isPendenzaMbt(Pendenza pendenza) {
        for (VocePendenza voce : pendenza.getVoci()) {
            if (voce.getTipoBollo() != null && voce.getHashDocumento() != null
                    && voce.getProvinciaResidenza() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Un {@code SoggettoDebitore} v3 puo' avere solo {@code tipo}+{@code identificativo} —
     * {@code anagrafica} non e' {@code required} nello schema {@code Soggetto}. Lo schema di
     * govpay-stampe-api invece richiede {@code Debtor.full_name}: senza questo controllo a
     * monte govpay-stampe risponderebbe 400, tradotto poi in 502 generico dal client HTTP.
     * Stesso stile del controllo MBT qui sopra — 422 esplicito prima di costruire il payload,
     * invece di un fallback silenzioso nel mapper (nessun precedente legacy per questo caso,
     * scelta di prodotto nuova per la v3).
     */
    public static boolean isAnagraficaDebitoreAssente(Pendenza pendenza) {
        return !StringUtils.hasText(primoSoggettoDebitore(pendenza).getAnagrafica());
    }

    /**
     * Per una pendenza v3 il debitore reale e' sempre {@code soggettiDebitori.get(0)} (indice
     * piu' basso, lista gia' ordinata) — vedi Javadoc di campo su {@code Pendenza}:
     * {@code debitoreIdentificativo}/{@code debitoreAnagrafica} sono placeholder fissi, mai il
     * debitore reale.
     */
    private static SoggettoDebitore primoSoggettoDebitore(Pendenza pendenza) {
        return pendenza.getOpzionePagamento().getPosizioneDebitoria().getSoggettiDebitori().get(0);
    }

    /**
     * Replica {@code console-api.AvvisoPdfPayloadMapper.ibanPostale}: il bollettino postale e'
     * attivo se la prima voce della pendenza ({@code voci.get(0)}, lista gia' ordinata per
     * {@code indice}, stesso principio di {@link #primoSoggettoDebitore}) ha un IBAN postale, di
     * accredito o (in subordine) di appoggio. A differenza di console-api (dove
     * {@code SingoloVersamento} porta gia' le relazioni JPA risolte), {@link VocePendenza} porta
     * solo le FK piatte ({@code idIbanAccredito}/{@code idIbanAppoggio}/{@code idTributo}): vanno
     * risolte qui tramite {@link #ibanAccreditoRepository}/{@link #tributoRepository}.
     */
    private IbanAccreditoEntity ibanPostale(Pendenza pendenza) {
        if (pendenza.getVoci() == null || pendenza.getVoci().isEmpty()) {
            return null;
        }
        VocePendenza prima = pendenza.getVoci().get(0);
        IbanAccreditoEntity accredito = ibanAccreditoDi(prima);
        if (isPostale(accredito)) {
            return accredito;
        }
        IbanAccreditoEntity appoggio = ibanAppoggioDi(prima);
        return isPostale(appoggio) ? appoggio : null;
    }

    private IbanAccreditoEntity ibanAccreditoDi(VocePendenza voce) {
        if (voce.getIdIbanAccredito() != null) {
            return ibanAccreditoRepository.findById(voce.getIdIbanAccredito()).orElse(null);
        }
        return voce.getIdTributo() != null
                ? tributoRepository.findById(voce.getIdTributo()).map(TributoEntity::getIbanAccredito).orElse(null)
                : null;
    }

    private IbanAccreditoEntity ibanAppoggioDi(VocePendenza voce) {
        if (voce.getIdIbanAppoggio() != null) {
            return ibanAccreditoRepository.findById(voce.getIdIbanAppoggio()).orElse(null);
        }
        return voce.getIdTributo() != null
                ? tributoRepository.findById(voce.getIdTributo()).map(TributoEntity::getIbanAppoggio).orElse(null)
                : null;
    }

    private static boolean isPostale(IbanAccreditoEntity iban) {
        return iban != null && Boolean.TRUE.equals(iban.getPostale());
    }

    private static Creditor mapCreditor(DominioEntity dominio) {
        Creditor c = new Creditor();
        c.setFiscalCode(dominio.getCodDominio());
        c.setBusinessName(tronca(dominio.getRagioneSociale(), MAX_BUSINESS_NAME));
        c.setCbillCode(dominio.getCbill());
        c.setPostalAuthMessage(tronca(dominio.getAutStampaPoste(), MAX_POSTAL_AUTH_MESSAGE));
        return c;
    }

    /**
     * {@code addressLine1}/{@code addressLine2} troncano DOPO la concatenazione: i limiti dello
     * schema (50/70 caratteri) sono quelli imposti da pagoPA sul singolo campo del bollettino,
     * non sulle singole colonne DB che li compongono (es. {@code indirizzo varchar(70)} +
     * {@code civico varchar(16)} possono insieme superare 70) — troncare le colonne
     * singolarmente prima di concatenarle non basterebbe a garantire il rispetto del limite.
     */
    private static Debtor mapDebtor(SoggettoDebitore soggetto) {
        Debtor d = new Debtor();
        d.setFiscalCode(soggetto.getIdentificativo());
        d.setFullName(tronca(soggetto.getAnagrafica(), MAX_FULL_NAME));
        if (StringUtils.hasText(soggetto.getIndirizzo())) {
            StringBuilder line1 = new StringBuilder(soggetto.getIndirizzo());
            if (StringUtils.hasText(soggetto.getCivico())) {
                line1.append(", ").append(soggetto.getCivico());
            }
            d.setAddressLine1(tronca(line1.toString(), MAX_ADDRESS_LINE));
        }
        StringBuilder line2 = new StringBuilder();
        if (StringUtils.hasText(soggetto.getCap())) {
            line2.append(soggetto.getCap()).append(' ');
        }
        if (StringUtils.hasText(soggetto.getLocalita())) {
            line2.append(soggetto.getLocalita());
        }
        if (StringUtils.hasText(soggetto.getProvincia())) {
            line2.append(" (").append(soggetto.getProvincia()).append(')');
        }
        if (!line2.isEmpty()) {
            d.setAddressLine2(tronca(line2.toString().trim(), MAX_ADDRESS_LINE));
        }
        return d;
    }

    private static Amount mapFullAmount(Pendenza pendenza, DominioEntity dominio, IbanAccreditoEntity postale) {
        Amount amount = new Amount();
        amount.setAmount(pendenza.getImporto());
        amount.setNoticeNumber(pendenza.getNumeroAvviso());
        amount.setQrcode(AvvisoMapper.buildQrcode(pendenza, dominio));
        amount.setDueDate(AvvisoMapper.resolveDataScadenza(pendenza));
        if (postale != null) {
            amount.setIban(mapIban(postale));
        }
        return amount;
    }

    /**
     * Dati del conto corrente postale. Si inviano i valori grezzi: numero di CC, datamatrix e i
     * fallback (intestatario assente → ente creditore, autorizzazione dell'IBAN che prevale su
     * quella del dominio) sono derivati da {@code govpay-stampe}
     * ({@code BaseAvvisoMapper.impostaDatiPostaliNellaRata}, {@code getAutorizzazionePostale}),
     * che replica V1 — stesso pattern di {@code console-api.AvvisoPdfPayloadMapper.mapIban}.
     */
    private static Iban mapIban(IbanAccreditoEntity postale) {
        Iban iban = new Iban();
        iban.setIbanCode(postale.getCodIban());
        iban.setOwnerBusinessName(tronca(postale.getIntestatario(), MAX_OWNER_BUSINESS_NAME));
        iban.setPostalAuthMessage(tronca(postale.getAutStampaPoste(), MAX_POSTAL_AUTH_MESSAGE));
        return iban;
    }

    /**
     * {@code null} (nessuna lingua secondaria) per {@link LinguaSecondaria#FALSE}. Non-private:
     * testata direttamente (stesso pattern di {@code console-api.AvvisoPdfPayloadMapper}).
     */
    static Languages toClientLanguage(LinguaSecondaria input) {
        if (input == null || input == LinguaSecondaria.FALSE) {
            return null;
        }
        return switch (input) {
            case DE -> Languages.DE;
            case EN -> Languages.EN;
            case FR -> Languages.FR;
            case SL -> Languages.SL;
            default -> null;
        };
    }

    /**
     * {@code causaleTradotta}/{@code informativaImportoTradotta} (parametri di richiesta) vanno
     * rispettivamente in {@code title}/{@code informativaImporto}: se assenti restano
     * {@code null} — nessun fallback sul testo italiano, per non mostrare testo non tradotto
     * etichettato come lingua secondaria (stessa scelta di govpay-console-api per i suoi
     * equivalenti).
     *
     * <p>{@code informativaImportoTradotta} viene sempre passato cosi' com'e': e'
     * govpay-stampe-api (non questo mapper) a leggerlo solo se
     * {@code PaymentNotice.informativaImporto} e' valorizzato, stesso vincolo del legacy.</p>
     */
    private static NoticeMetadataSecondLanguage buildSecondLanguage(Languages lang, String causaleTradotta,
            String informativaImportoTradotta) {
        NoticeMetadataSecondLanguage sl = new NoticeMetadataSecondLanguage();
        sl.setBilinguism(Boolean.TRUE);
        sl.setLanguage(lang);
        sl.setTitle(causaleTradotta);
        sl.setInformativaImporto(informativaImportoTradotta);
        return sl;
    }

    /**
     * Le colonne di {@code domini} sono {@code varchar(255)}, il campo corrispondente del
     * contratto stampe ha un {@code maxLength} piu' stretto e {@code govpay-stampe} lo valida
     * ({@code @Size}): troncare un campo di sola resa grafica stampa un avviso con
     * l'intestazione accorciata, non troncarlo farebbe fallire l'intero PDF con un 400.
     */
    private static String tronca(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
