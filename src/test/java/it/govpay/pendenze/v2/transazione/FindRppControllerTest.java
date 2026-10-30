package it.govpay.pendenze.v2.transazione;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import it.govpay.common.auth.GovpayPasswordEncoder;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.TipoTributoEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.TipoTributoRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.common.repository.TipoVersamentoRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;
import it.govpay.pendenze.v2.pendenza.AllegatoRepository;

/**
 * Verifica {@code GET /v2/rpp} (ricerca trasversale delle RPT dell'applicazione autenticata) a
 * livello di integrazione — stesso impianto di {@link RppV2ControllerTest} (duplicato per lo
 * stesso motivo documentato in {@code PendenzaV2ControllerTest}: nessuna superclasse comune tra
 * controller di test analoghi). Le righe {@code rpt} si creano direttamente dal repository,
 * come in {@link RppV2ControllerTest}: questa libreria non ha un flusso applicativo che le
 * scriva.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FindRppControllerTest.BasicAuthDiDefaultConfig.class)
class FindRppControllerTest {

    private static final String PRINCIPAL = "A2A-TEST";
    private static final String PASSWORD = "test-password";
    private static final String DOMINIO = "12345678901";

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 3, 10, 10, 0, 0, 0, ZoneOffset.UTC);

    @TestConfiguration
    static class BasicAuthDiDefaultConfig {
        @Bean
        MockMvcBuilderCustomizer basicAuthDiDefaultCustomizer() {
            return builder -> builder.defaultRequest(
                    MockMvcRequestBuilders.get("/").with(httpBasic(PRINCIPAL, PASSWORD)));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicazioneRepository applicazioneRepository;

    @Autowired
    private UtenzaRepository utenzaRepository;

    @Autowired
    private AclRepository aclRepository;

    @Autowired
    private GovpayPasswordEncoder passwordEncoder;

    @Autowired
    private DominioRepository dominioRepository;

    @Autowired
    private TipoVersamentoRepository tipoVersamentoRepository;

    @Autowired
    private TipoVersamentoDominioRepository tipoVersamentoDominioRepository;

    @Autowired
    private TipoTributoRepository tipoTributoRepository;

    @Autowired
    private TributoRepository tributoRepository;

    @Autowired
    private PosizioneDebitoriaRepository posizioneDebitoriaRepository;

    @Autowired
    private RptRepository rptRepository;

    @Autowired
    private PendenzaRepository pendenzaRepository;

    @Autowired
    private AllegatoRepository allegatoRepository;

    @BeforeEach
    void creaAnagrafiche() {
        UtenzaEntity utenza = creaUtenza(PRINCIPAL, PASSWORD);

        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(PRINCIPAL)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(utenza.getId())
                .build());

        DominioEntity dominio = dominioRepository.save(DominioEntity.builder()
                .codDominio(DOMINIO)
                .ragioneSociale("Comune di Test")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());

        TipoVersamentoEntity tipoVersamento = new TipoVersamentoEntity();
        tipoVersamento.setCodTipoVersamento("DIRITTI_SEGRETERIA");
        tipoVersamento.setDescrizione("Diritti di segreteria");
        tipoVersamento.setAbilitato(true);
        tipoVersamento.setPagaTerzi(false);
        tipoVersamento.setBoAbilitato(false);
        tipoVersamento.setPagAbilitato(false);
        tipoVersamento.setAvvMailPromAvvAbilitato(false);
        tipoVersamento.setAvvMailPromRicAbilitato(false);
        tipoVersamento.setAvvMailPromScadAbilitato(false);
        tipoVersamento.setAvvAppIoPromAvvAbilitato(false);
        tipoVersamento.setAvvAppIoPromRicAbilitato(false);
        tipoVersamento.setAvvAppIoPromScadAbilitato(false);
        tipoVersamentoRepository.save(tipoVersamento);

        TipoTributoEntity tipoTributo = new TipoTributoEntity();
        tipoTributo.setCodTributo("DIRITTI_SEGRETERIA");
        tipoTributo.setDescrizione("Diritti di segreteria");
        tipoTributoRepository.save(tipoTributo);

        TipoVersamentoDominioEntity override = new TipoVersamentoDominioEntity();
        override.setTipoVersamento(tipoVersamento);
        override.setDominio(dominio);
        tipoVersamentoDominioRepository.save(override);

        TributoEntity tributo = new TributoEntity();
        tributo.setAbilitato(true);
        tributo.setDominio(dominio);
        tributo.setTipoTributo(tipoTributo);
        tributoRepository.save(tributo);
    }

    @AfterEach
    void pulisci() {
        allegatoRepository.deleteAll();
        rptRepository.deleteAll();
        posizioneDebitoriaRepository.deleteAll();
        tipoVersamentoDominioRepository.deleteAll();
        tipoVersamentoRepository.deleteAll();
        tributoRepository.deleteAll();
        tipoTributoRepository.deleteAll();
        dominioRepository.deleteAll();
        applicazioneRepository.deleteAll();
        aclRepository.deleteAll();
        utenzaRepository.deleteAll();
    }

    // ── Scenari ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /v2/rpp senza filtri restituisce tutte le RPT dell'applicazione autenticata, su pendenze "
            + "diverse, con metadati e ordinamento di default dataMsgRichiesta DESC")
    void senzaFiltriTutteLeRptDellApplicazione() throws Exception {
        Pendenza p1 = creaPendenza("pos-find-rpp-1", "pend-find-rpp-1");
        Pendenza p2 = creaPendenza("pos-find-rpp-2", "pend-find-rpp-2");
        creaRpt(p1, DOMINIO, "IUV100000001", "CCP100000001", T0, T0.plusMinutes(5), 0);
        creaRpt(p1, DOMINIO, "IUV100000002", "CCP100000002", T0.plusDays(1), null, null);
        creaRpt(p2, DOMINIO, "IUV100000003", "CCP100000003", T0.plusDays(2), T0.plusDays(2), 1);

        mockMvc.perform(get("/v2/rpp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.numRisultati").value(3))
                .andExpect(jsonPath("$.numPagine").value(1))
                .andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.risultatiPerPagina").value(25))
                .andExpect(jsonPath("$.prossimiRisultati").doesNotExist())
                // dataMsgRichiesta DESC: la RPT di p2 (T0+2gg) per prima, poi le due di p1.
                .andExpect(jsonPath("$.risultati[0].pendenza.idPendenza").value("pend-find-rpp-2"))
                .andExpect(jsonPath("$.risultati[1].pendenza.idPendenza").value("pend-find-rpp-1"))
                .andExpect(jsonPath("$.risultati[2].pendenza.idPendenza").value("pend-find-rpp-1"))
                .andExpect(jsonPath("$.risultati[0].stato").value("RT_ACCETTATA_PA"))
                .andExpect(jsonPath("$.risultati[0].rpt").exists())
                .andExpect(jsonPath("$.risultati[0].rt").exists());
    }

    @Test
    @DisplayName("GET /v2/rpp non mostra le RPT di un'altra applicazione, e ignora idA2A in query string "
            + "(fedele al legacy: sovrascritto con l'applicazione del chiamante)")
    void nonAttraversaLeApplicazioniEIgnoraIdA2A() throws Exception {
        Pendenza mia = creaPendenza("pos-find-rpp-mia", "pend-find-rpp-mia");
        creaRpt(mia, DOMINIO, "IUV200000001", "CCP200000001", T0, T0, 0);

        creaAltraApplicazione("A2A-ALTRO", "altra-password");
        Pendenza altrui = creaPendenzaComeAltraApplicazione();
        creaRpt(altrui, DOMINIO, "IUV200000002", "CCP200000002", T0, T0, 0);

        mockMvc.perform(get("/v2/rpp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1))
                .andExpect(jsonPath("$.risultati[0].pendenza.idPendenza").value("pend-find-rpp-mia"));

        mockMvc.perform(get("/v2/rpp").queryParam("idA2A", "A2A-ALTRO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1))
                .andExpect(jsonPath("$.risultati[0].pendenza.idPendenza").value("pend-find-rpp-mia"));
    }

    @Test
    @DisplayName("GET /v2/rpp filtra per idDominio direttamente su rpt.cod_dominio; un dominio inesistente "
            + "da' 0 risultati, non un errore")
    void filtraPerIdDominio() throws Exception {
        Pendenza pendenza = creaPendenza("pos-find-rpp-dom", "pend-find-rpp-dom");
        creaRpt(pendenza, DOMINIO, "IUV300000001", "CCP300000001", T0, T0, 0);
        creaRpt(pendenza, "99999999999", "IUV300000002", "CCP300000002", T0, T0, 0);

        mockMvc.perform(get("/v2/rpp").queryParam("idDominio", DOMINIO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));

        mockMvc.perform(get("/v2/rpp").queryParam("idDominio", "99999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));

        mockMvc.perform(get("/v2/rpp").queryParam("idDominio", "00000000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(0))
                .andExpect(jsonPath("$.numPagine").value(1))
                .andExpect(jsonPath("$.risultati").isEmpty());
    }

    @Test
    @DisplayName("GET /v2/rpp filtra per esitoPagamento tradotto nel codice numerico pagoPA "
            + "(ESEGUITO=0, NON_ESEGUITO=1, RIFIUTATO=101)")
    void filtraPerEsitoPagamento() throws Exception {
        Pendenza pendenza = creaPendenza("pos-find-rpp-esito", "pend-find-rpp-esito");
        Rpt eseguita = creaRpt(pendenza, DOMINIO, "IUV400000001", "CCP400000001", T0, T0, 0);
        creaRpt(pendenza, DOMINIO, "IUV400000002", "CCP400000002", T0.plusHours(1), T0.plusHours(1), 1);
        creaRpt(pendenza, DOMINIO, "IUV400000003", "CCP400000003", T0.plusHours(2), null, 101);

        mockMvc.perform(get("/v2/rpp").queryParam("esitoPagamento", "ESEGUITO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));
        mockMvc.perform(get("/v2/rpp").queryParam("esitoPagamento", "NON_ESEGUITO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));
        mockMvc.perform(get("/v2/rpp").queryParam("esitoPagamento", "RIFIUTATO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));
        mockMvc.perform(get("/v2/rpp").queryParam("esitoPagamento", "DECORRENZA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(0));

        // Il valore filtrato e' proprio quello della RPT con codEsitoPagamento=0.
        mockMvc.perform(get("/v2/rpp").queryParam("esitoPagamento", "ESEGUITO").queryParam("iuv", eseguita.getIuv()))
                .andExpect(jsonPath("$.numRisultati").value(1));
    }

    @Test
    @DisplayName("GET /v2/rpp con esitoPagamento non riconosciuto risponde 400")
    void esitoPagamentoNonRiconosciutoE400() throws Exception {
        mockMvc.perform(get("/v2/rpp").queryParam("esitoPagamento", "BOH"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /v2/rpp filtra per dataRptDa/dataRptA (dataMsgRichiesta) e dataRtDa/dataRtA "
            + "(dataMsgRicevuta), estremi inclusi")
    void filtraPerRangeDiDate() throws Exception {
        Pendenza pendenza = creaPendenza("pos-find-rpp-date", "pend-find-rpp-date");
        creaRpt(pendenza, DOMINIO, "IUV500000001", "CCP500000001", T0, T0.plusDays(10), 0);
        creaRpt(pendenza, DOMINIO, "IUV500000002", "CCP500000002", T0.plusDays(1), T0.plusDays(11), 0);
        creaRpt(pendenza, DOMINIO, "IUV500000003", "CCP500000003", T0.plusDays(2), null, null);

        mockMvc.perform(get("/v2/rpp")
                        .queryParam("dataRptDa", T0.plusDays(1).toString())
                        .queryParam("dataRptA", T0.plusDays(2).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(2));

        mockMvc.perform(get("/v2/rpp").queryParam("dataRptA", T0.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));

        // La RPT senza RT (dataMsgRicevuta null) non rientra in nessun range su dataRt.
        mockMvc.perform(get("/v2/rpp").queryParam("dataRtDa", T0.plusDays(10).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(2));

        mockMvc.perform(get("/v2/rpp")
                        .queryParam("dataRtDa", T0.plusDays(11).toString())
                        .queryParam("dataRtA", T0.plusDays(11).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));
    }

    @Test
    @DisplayName("GET /v2/rpp filtra iuv e ccp come 'contiene' case-insensitive (ilike ANYWHERE del legacy), "
            + "non per uguaglianza")
    void filtraIuvECcpComeContiene() throws Exception {
        Pendenza pendenza = creaPendenza("pos-find-rpp-like", "pend-find-rpp-like");
        creaRpt(pendenza, DOMINIO, "IUV600000001", "CCPABC000001", T0, T0, 0);
        creaRpt(pendenza, DOMINIO, "IUV600000002", "CCPXYZ000002", T0, T0, 0);

        mockMvc.perform(get("/v2/rpp").queryParam("iuv", "600000"))
                .andExpect(jsonPath("$.numRisultati").value(2));
        mockMvc.perform(get("/v2/rpp").queryParam("iuv", "iuv600000001"))
                .andExpect(jsonPath("$.numRisultati").value(1));
        mockMvc.perform(get("/v2/rpp").queryParam("ccp", "abc"))
                .andExpect(jsonPath("$.numRisultati").value(1));
        mockMvc.perform(get("/v2/rpp").queryParam("ccp", "NESSUNO"))
                .andExpect(jsonPath("$.numRisultati").value(0));
    }

    @Test
    @DisplayName("GET /v2/rpp filtra per idPendenza (esatto) e idDebitore (case-insensitive su "
            + "srcDebitoreIdentificativo)")
    void filtraPerIdPendenzaEIdDebitore() throws Exception {
        Pendenza p1 = creaPendenza("pos-find-rpp-pend-1", "pend-find-rpp-pend-1");
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoPosizioneConDebitore("pos-find-rpp-pend-2", "pend-find-rpp-pend-2",
                                "RSSMRA80A01H501U")))
                .andExpect(status().isCreated());
        Pendenza p2 = trovaPendenza(PRINCIPAL, "pend-find-rpp-pend-2");
        creaRpt(p1, DOMINIO, "IUV700000001", "CCP700000001", T0, T0, 0);
        creaRpt(p2, DOMINIO, "IUV700000002", "CCP700000002", T0, T0, 0);

        mockMvc.perform(get("/v2/rpp").queryParam("idPendenza", "pend-find-rpp-pend-1"))
                .andExpect(jsonPath("$.numRisultati").value(1))
                .andExpect(jsonPath("$.risultati[0].pendenza.idPendenza").value("pend-find-rpp-pend-1"));
        mockMvc.perform(get("/v2/rpp").queryParam("idPendenza", "pend-find-rpp"))
                .andExpect(jsonPath("$.numRisultati").value(0));
        mockMvc.perform(get("/v2/rpp").queryParam("idDebitore", "rssmra80a01h501u"))
                .andExpect(jsonPath("$.numRisultati").value(1))
                .andExpect(jsonPath("$.risultati[0].pendenza.idPendenza").value("pend-find-rpp-pend-2"));
    }

    @Test
    @DisplayName("GET /v2/rpp: idPagamento e' accettato ma non filtra nulla — bug del legacy replicato "
            + "(mai passato al filtro SQL reale)")
    void idPagamentoIgnorato() throws Exception {
        Pendenza pendenza = creaPendenza("pos-find-rpp-idpag", "pend-find-rpp-idpag");
        creaRpt(pendenza, DOMINIO, "IUV800000001", "CCP800000001", T0, T0, 0);
        creaRpt(pendenza, DOMINIO, "IUV800000002", "CCP800000002", T0, T0, 0);

        mockMvc.perform(get("/v2/rpp").queryParam("idPagamento", "IDENTIFICATIVO-CHE-NON-ESISTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(2));
    }

    @Test
    @DisplayName("GET /v2/rpp pagina con risultatiPerPagina, con e senza metadati (metadatiPaginazione=false: "
            + "niente numRisultati/numPagine, prossimiRisultati dal limit+1)")
    void paginazione() throws Exception {
        Pendenza pendenza = creaPendenza("pos-find-rpp-pag", "pend-find-rpp-pag");
        creaRpt(pendenza, DOMINIO, "IUV900000001", "CCP900000001", T0, T0, 0);
        creaRpt(pendenza, DOMINIO, "IUV900000002", "CCP900000002", T0.plusHours(1), T0, 0);
        creaRpt(pendenza, DOMINIO, "IUV900000003", "CCP900000003", T0.plusHours(2), T0, 0);

        mockMvc.perform(get("/v2/rpp").queryParam("risultatiPerPagina", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(3))
                .andExpect(jsonPath("$.numPagine").value(2))
                .andExpect(jsonPath("$.risultati.length()").value(2))
                .andExpect(jsonPath("$.prossimiRisultati").value(containsString("pagina=2")));

        mockMvc.perform(get("/v2/rpp").queryParam("risultatiPerPagina", "2").queryParam("metadatiPaginazione", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").doesNotExist())
                .andExpect(jsonPath("$.numPagine").doesNotExist())
                .andExpect(jsonPath("$.risultati.length()").value(2))
                .andExpect(jsonPath("$.prossimiRisultati").value(containsString("pagina=2")));

        mockMvc.perform(get("/v2/rpp").queryParam("risultatiPerPagina", "2").queryParam("pagina", "2")
                        .queryParam("metadatiPaginazione", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").doesNotExist())
                .andExpect(jsonPath("$.risultati.length()").value(1))
                .andExpect(jsonPath("$.prossimiRisultati").doesNotExist());
    }

    @Test
    @DisplayName("GET /v2/rpp ordina per i campi del legacy (+dataRichiesta), rifiuta con 400 un campo non "
            + "ordinabile")
    void ordinamento() throws Exception {
        Pendenza p1 = creaPendenza("pos-find-rpp-ord-1", "pend-find-rpp-ord-1");
        Pendenza p2 = creaPendenza("pos-find-rpp-ord-2", "pend-find-rpp-ord-2");
        creaRpt(p1, DOMINIO, "IUV910000001", "CCP910000001", T0, T0, 0);
        creaRpt(p2, DOMINIO, "IUV910000002", "CCP910000002", T0.plusDays(1), T0, 0);

        mockMvc.perform(get("/v2/rpp").queryParam("ordinamento", "+dataRichiesta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risultati[0].pendenza.idPendenza").value("pend-find-rpp-ord-1"))
                .andExpect(jsonPath("$.risultati[1].pendenza.idPendenza").value("pend-find-rpp-ord-2"));

        mockMvc.perform(get("/v2/rpp").queryParam("ordinamento", "-stato,+dataRichiesta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risultati[*].pendenza.idPendenza")
                        .value(containsInAnyOrder("pend-find-rpp-ord-1", "pend-find-rpp-ord-2")));

        mockMvc.perform(get("/v2/rpp").queryParam("ordinamento", "-iuv"))
                .andExpect(status().isBadRequest());
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private UtenzaEntity creaUtenza(String principal, String password) {
        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(principal);
        utenza.setPrincipalOriginale(principal);
        utenza.setAbilitato(true);
        utenza.setAutorizzazioneTipiVersStar(false);
        utenza.setAutorizzazioneDominiStar(false);
        utenza.setPassword(passwordEncoder.encode(password));
        utenza = utenzaRepository.save(utenza);

        AclEntity acl = new AclEntity();
        acl.setServizio("API Pendenze");
        acl.setDiritti("RW");
        acl.setIdUtenza(utenza.getId());
        aclRepository.save(acl);

        return utenza;
    }

    private void creaAltraApplicazione(String principal, String password) {
        UtenzaEntity altraUtenza = creaUtenza(principal, password);
        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(principal)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(altraUtenza.getId())
                .build());
    }

    private static RequestPostProcessor comeApplicazione(String principal, String password) {
        return request -> {
            request.removeHeader(HttpHeaders.AUTHORIZATION);
            return httpBasic(principal, password).postProcessRequest(request);
        };
    }

    private static String corpoPosizione(String codDocumento, String idPendenza) {
        return corpoPosizioneConDebitore(codDocumento, idPendenza, "FRRPLA90C41H501Y");
    }

    private static String corpoPosizioneConDebitore(String codDocumento, String idPendenza, String idDebitore) {
        return """
                {
                  "codDocumento": "%s",
                  "idDominio": "%s",
                  "descrizione": "test wrapper v2 find rpp",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "%s",
                                           "anagrafica": "Mario Rossi" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "%s",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-%s", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """.formatted(codDocumento, DOMINIO, idDebitore, idPendenza, idPendenza);
    }

    private Pendenza creaPendenza(String codDocumento, String idPendenza) throws Exception {
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoPosizione(codDocumento, idPendenza)))
                .andExpect(status().isCreated());
        return trovaPendenza(PRINCIPAL, idPendenza);
    }

    private Pendenza creaPendenzaComeAltraApplicazione() throws Exception {
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-ALTRO")
                        .with(comeApplicazione("A2A-ALTRO", "altra-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoPosizione("pos-find-rpp-altrui", "pend-find-rpp-altrui")))
                .andExpect(status().isCreated());
        return trovaPendenza("A2A-ALTRO", "pend-find-rpp-altrui");
    }

    private Pendenza trovaPendenza(String idA2A, String idPendenza) {
        Long idApplicazione = applicazioneRepository.findByCodApplicazione(idA2A).orElseThrow().getId();
        return pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, idPendenza)
                .orElseThrow();
    }

    /**
     * RPT con la stessa RT di fixture di {@link RppV2ControllerTest} (il contenuto di
     * {@code rpt}/{@code rt} nella risposta viene dal fixture, non dalla chiave iuv/ccp): la RT
     * e' presente anche quando {@code dataMsgRicevuta} e' {@code null}, perche' senza RT la RPT
     * non sarebbe ricostruibile (nessun {@code xml_rpt} in questi test) — qui conta solo la
     * selezione delle righe, non la coerenza RT/data.
     */
    private Rpt creaRpt(Pendenza pendenza, String codDominio, String iuv, String ccp, OffsetDateTime dataRichiesta,
            OffsetDateTime dataRicevuta, Integer codEsito) throws IOException {
        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv(iuv);
        rpt.setIur(ccp);
        rpt.setCodDominio(codDominio);
        rpt.setVersione("SANP_240");
        rpt.setStato("RT_ACCETTATA_PA");
        rpt.setDescrizioneStato("Pagamento eseguito con successo");
        rpt.setXmlRt(fixtureRt());
        rpt.setDataMsgRichiesta(dataRichiesta);
        rpt.setDataMsgRicevuta(dataRicevuta);
        rpt.setCodEsitoPagamento(codEsito);
        rpt.setCodStazione("STAZIONE-TEST");
        rpt.setCodMsgRichiesta("msg-" + ccp);
        rpt.setDataAggiornamentoStato(dataRicevuta != null ? dataRicevuta : dataRichiesta);
        return rptRepository.save(rpt);
    }

    private static byte[] fixtureRt() throws IOException {
        try (InputStream in = FindRppControllerTest.class.getResourceAsStream("/rt/rt-v2-ok.xml")) {
            return in.readAllBytes();
        }
    }
}
