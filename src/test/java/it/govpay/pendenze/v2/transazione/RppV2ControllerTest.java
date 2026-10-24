package it.govpay.pendenze.v2.transazione;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

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

import java.io.IOException;
import java.io.InputStream;

/**
 * Verifica {@code GET /v2/rpp/{idDominio}/{iuv}/{ccp}} e varianti {@code /rpt}/{@code /rt} a
 * livello di integrazione — stesso impianto di
 * {@code it.govpay.pendenze.v2.pendenza.PendenzaV2ControllerTest} (duplicato per lo stesso
 * motivo documentato lì: nessuna superclasse comune tra controller di test analoghi).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RppV2ControllerTest.BasicAuthDiDefaultConfig.class)
class RppV2ControllerTest {

    private static final String PRINCIPAL = "A2A-TEST";
    private static final String PASSWORD = "test-password";

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
                .codApplicazione("A2A-TEST")
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(utenza.getId())
                .build());

        DominioEntity dominio = dominioRepository.save(DominioEntity.builder()
                .codDominio("12345678901")
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

    private UtenzaEntity creaUtenza(String principal, String password) {
        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(principal);
        utenza.setPrincipalOriginale(principal);
        utenza.setAbilitato(true);
        utenza.setAutorizzazioneTipiVersStar(false);
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

    private static org.springframework.test.web.servlet.request.RequestPostProcessor comeApplicazione(
            String principal, String password) {
        return request -> {
            request.removeHeader(HttpHeaders.AUTHORIZATION);
            return httpBasic(principal, password).postProcessRequest(request);
        };
    }

    private String creaPosizioneConPendenza(String idPosizioneDebitoria, String codDominio, String idPendenza)
            throws Exception {
        String body = """
                {
                  "codDocumento": "%s",
                  "idDominio": "%s",
                  "descrizione": "test wrapper v2 rpp",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y",
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
                """.formatted(idPosizioneDebitoria, codDominio, idPendenza, idPendenza);

        String risposta = mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(risposta, "$.opzioniPagamento[0].pendenze[0].numeroAvviso");
    }

    private static byte[] fixtureRt(String nome) throws IOException {
        try (InputStream in = RppV2ControllerTest.class.getResourceAsStream("/rt/" + nome)) {
            return in.readAllBytes();
        }
    }

    /**
     * RPT con {@code xml_rpt} assente (nessuna riga in rendicontazioni) e RT presente: esercita
     * la ricostruzione (stesso fixture/scenario di
     * {@code PendenzaV2ControllerTest#getPendenzaConRppPopolatoSerializzaRtNelFormatoReale}).
     */
    private Rpt creaRpt(Long idVersamento, String iuv, String ccp) throws IOException {
        Rpt rpt = new Rpt();
        rpt.setIdVersamento(idVersamento);
        rpt.setIuv(iuv);
        rpt.setIur(ccp);
        rpt.setCodDominio("12345678901");
        rpt.setVersione("SANP_240");
        rpt.setStato("RT_ACCETTATA_PA");
        rpt.setDescrizioneStato("Pagamento eseguito con successo");
        rpt.setXmlRt(fixtureRt("rt-v2-ok.xml"));
        return rptRepository.save(rpt);
    }

    private Pendenza trovaPendenza(String idPendenza) {
        Long idApplicazione = applicazioneRepository.findByCodApplicazione("A2A-TEST").orElseThrow().getId();
        return pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, idPendenza)
                .orElseThrow();
    }

    @Test
    @DisplayName("GET /v2/rpp/{idDominio}/{iuv}/{ccp} restituisce 200 (non il 201 dichiarato dallo "
            + "YAML legacy, mai realmente emesso) con stato/rpt/rt/pendenza popolati")
    void getRppRestituisceIlDettaglio() throws Exception {
        creaPosizioneConPendenza("pos-v2-rpp-1", "12345678901", "pendenza-v2-rpp-1");
        Pendenza pendenza = trovaPendenza("pendenza-v2-rpp-1");
        creaRpt(pendenza.getId(), "IUV900000010", "RT900000010");

        mockMvc.perform(get("/v2/rpp/{idDominio}/{iuv}/{ccp}", "12345678901", "IUV900000010", "RT900000010"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.stato").value("RT_ACCETTATA_PA"))
                .andExpect(jsonPath("$.dettaglioStato").value("Pagamento eseguito con successo"))
                // Il contenuto di rt/rpt viene dal fixture XML (sempre RT900000003/IUV900000003,
                // vedi Javadoc di creaRpt), non dalla chiave iuv/ccp usata per la ricerca qui sotto.
                .andExpect(jsonPath("$.rt.receiptId").value("RT900000003"))
                .andExpect(jsonPath("$.rt.nodeType").doesNotExist())
                .andExpect(jsonPath("$.rpt.creditorReferenceId").value("IUV900000003"))
                .andExpect(jsonPath("$.pendenza.idPendenza").value("pendenza-v2-rpp-1"));
    }

    @Test
    @DisplayName("GET /v2/rpp/{idDominio}/{iuv}/{ccp} restituisce 404 se la RPT non esiste")
    void getRppRestituisce404SeNonEsiste() throws Exception {
        mockMvc.perform(get("/v2/rpp/{idDominio}/{iuv}/{ccp}", "12345678901", "IUV-INESISTENTE", "CCP-INESISTENTE"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")));
    }

    @Test
    @DisplayName("GET /v2/rpp/{idDominio}/{iuv}/{ccp} restituisce 403 se la RPT appartiene a un'altra applicazione")
    void getRppRestituisce403SeApplicazioneDiversa() throws Exception {
        creaAltraApplicazione("A2A-ALTRO", "altra-password");

        String body = """
                {
                  "codDocumento": "pos-v2-rpp-altro-1",
                  "idDominio": "12345678901",
                  "descrizione": "test altra applicazione",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y",
                                           "anagrafica": "Mario Rossi" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-v2-rpp-altro-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-altro-rpp-1", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-ALTRO")
                        .with(comeApplicazione("A2A-ALTRO", "altra-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        Long idApplicazioneAltro = applicazioneRepository.findByCodApplicazione("A2A-ALTRO").orElseThrow().getId();
        Pendenza pendenzaAltro = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazioneAltro,
                        "pendenza-v2-rpp-altro-1")
                .orElseThrow();
        creaRpt(pendenzaAltro.getId(), "IUV900000011", "RT900000011");

        mockMvc.perform(get("/v2/rpp/{idDominio}/{iuv}/{ccp}", "12345678901", "IUV900000011", "RT900000011"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")));
    }

    @Test
    @DisplayName("GET /v2/rpp/{idDominio}/{iuv}/{ccp}/rpt con Accept: application/json restituisce il "
            + "contenuto reale (data nudo, non l'envelope) — non la rappresentazione interna di JsonNode")
    void getRptJsonRestituisceIlContenutoReale() throws Exception {
        creaPosizioneConPendenza("pos-v2-rpt-1", "12345678901", "pendenza-v2-rpt-1");
        Pendenza pendenza = trovaPendenza("pendenza-v2-rpt-1");
        creaRpt(pendenza.getId(), "IUV900000020", "RT900000020");

        mockMvc.perform(get("/v2/rpp/{idDominio}/{iuv}/{ccp}/rpt", "12345678901", "IUV900000020", "RT900000020")
                        .header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Dal fixture XML (sempre RT900000003/IUV900000003), non dalla chiave di ricerca.
                .andExpect(jsonPath("$.creditorReferenceId").value("IUV900000003"))
                .andExpect(jsonPath("$.nodeType").doesNotExist());
    }

    @Test
    @DisplayName("GET /v2/rpp/{idDominio}/{iuv}/{ccp}/rpt senza Accept (o con un Accept non riconosciuto) "
            + "risponde comunque XML, mai 406 — stesso default del legacy, mai la negoziazione standard di Spring")
    void getRptSenzaAcceptRestituisceXmlMai406() throws Exception {
        creaPosizioneConPendenza("pos-v2-rpt-2", "12345678901", "pendenza-v2-rpt-2");
        Pendenza pendenza = trovaPendenza("pendenza-v2-rpt-2");
        creaRpt(pendenza.getId(), "IUV900000021", "RT900000021");

        mockMvc.perform(get("/v2/rpp/{idDominio}/{iuv}/{ccp}/rpt", "12345678901", "IUV900000021", "RT900000021")
                        .header(HttpHeaders.ACCEPT, "text/plain"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_XML));
    }

    @Test
    @DisplayName("GET /v2/rpp/{idDominio}/{iuv}/{ccp}/rt con Accept: application/json restituisce il contenuto reale")
    void getRtJsonRestituisceIlContenutoReale() throws Exception {
        creaPosizioneConPendenza("pos-v2-rt-1", "12345678901", "pendenza-v2-rt-1");
        Pendenza pendenza = trovaPendenza("pendenza-v2-rt-1");
        creaRpt(pendenza.getId(), "IUV900000030", "RT900000030");

        mockMvc.perform(get("/v2/rpp/{idDominio}/{iuv}/{ccp}/rt", "12345678901", "IUV900000030", "RT900000030")
                        .header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Dal fixture XML (sempre RT900000003/IUV900000003), non dalla chiave di ricerca.
                .andExpect(jsonPath("$.receiptId").value("RT900000003"))
                .andExpect(jsonPath("$.nodeType").doesNotExist());
    }

    @Test
    @DisplayName("GET /v2/rpp/{idDominio}/{iuv}/{ccp}/rt senza Accept risponde XML (passthrough del byte[] "
            + "originale), mai 406")
    void getRtSenzaAcceptRestituisceXmlMai406() throws Exception {
        creaPosizioneConPendenza("pos-v2-rt-2", "12345678901", "pendenza-v2-rt-2");
        Pendenza pendenza = trovaPendenza("pendenza-v2-rt-2");
        creaRpt(pendenza.getId(), "IUV900000031", "RT900000031");

        mockMvc.perform(get("/v2/rpp/{idDominio}/{iuv}/{ccp}/rt", "12345678901", "IUV900000031", "RT900000031"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_XML));
    }

    @Test
    @DisplayName("GET /v2/rpp/{idDominio}/{iuv}/{ccp}/rt con Accept: application/pdf prova a generare la "
            + "stampa via govpay-stampe-api — 503 nel profilo di test (base-url non configurato), "
            + "riusando la stessa catena di GET .../ricevute/{iur}/stampa (v3)")
    void getRtPdfProvaAGenerareLaStampa() throws Exception {
        creaPosizioneConPendenza("pos-v2-rt-3", "12345678901", "pendenza-v2-rt-3");
        Pendenza pendenza = trovaPendenza("pendenza-v2-rt-3");
        creaRpt(pendenza.getId(), "IUV900000032", "RT900000032");

        mockMvc.perform(get("/v2/rpp/{idDominio}/{iuv}/{ccp}/rt", "12345678901", "IUV900000032", "RT900000032")
                        .header(HttpHeaders.ACCEPT, "application/pdf"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")));
    }

    @Test
    @DisplayName("GET /v2/rpp/{idDominio}/{iuv}/{ccp}/rt restituisce 404 se la RPT esiste ma la RT non e' "
            + "ancora stata acquisita — stesso trattamento (nessuna distinzione) del legacy")
    void getRtRestituisce404SeRtNonAncoraAcquisita() throws Exception {
        creaPosizioneConPendenza("pos-v2-rt-4", "12345678901", "pendenza-v2-rt-4");
        Pendenza pendenza = trovaPendenza("pendenza-v2-rt-4");

        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv("IUV900000033");
        rpt.setIur("RT900000033");
        rpt.setCodDominio("12345678901");
        rpt.setVersione("SANP_240");
        rpt.setStato("RPT_ACCETTATA_NODO");
        rpt.setDescrizioneStato("In attesa di riscontro");
        rptRepository.save(rpt);

        mockMvc.perform(get("/v2/rpp/{idDominio}/{iuv}/{ccp}/rt", "12345678901", "IUV900000033", "RT900000033"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")));
    }
}
