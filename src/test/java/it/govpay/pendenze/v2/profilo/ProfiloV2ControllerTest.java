package it.govpay.pendenze.v2.profilo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import it.govpay.common.auth.GovpayPasswordEncoder;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.TipoVersamentoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.security.UtenzaDominioEntity;
import it.govpay.pendenze.security.UtenzaDominioRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;
import it.govpay.pendenze.security.UtenzaTipoVersamentoEntity;
import it.govpay.pendenze.security.UtenzaTipoVersamentoRepository;

/**
 * Verifica {@code GET /v2/profilo} a livello di integrazione — stesso impianto di
 * {@code RppV2ControllerTest} (duplicato per lo stesso motivo documentato li': nessuna
 * superclasse comune tra controller di test analoghi). A differenza degli altri controller
 * v2, l'autenticazione qui non richiede nessuna {@code ApplicazioneEntity} (vedi Javadoc di
 * {@code PendenzeGovpayPrincipalLoader}: risolve solo {@code UtenzaEntity}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ProfiloV2ControllerTest.BasicAuthDiDefaultConfig.class)
class ProfiloV2ControllerTest {

    private static final String PRINCIPAL = "A2A-PROFILO-TEST";
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
    private UtenzaRepository utenzaRepository;

    @Autowired
    private GovpayPasswordEncoder passwordEncoder;

    @Autowired
    private DominioRepository dominioRepository;

    @Autowired
    private UnitaOperativaRepository unitaOperativaRepository;

    @Autowired
    private UtenzaDominioRepository utenzaDominioRepository;

    @Autowired
    private TipoVersamentoRepository tipoVersamentoRepository;

    @Autowired
    private UtenzaTipoVersamentoRepository utenzaTipoVersamentoRepository;

    private DominioEntity dominioUno;
    private DominioEntity dominioDue;
    private DominioEntity dominioNonAutorizzato;
    private TipoVersamentoEntity tipoVersamentoUno;
    private TipoVersamentoEntity tipoVersamentoNonAutorizzato;

    @BeforeEach
    void creaAnagrafiche() {
        dominioUno = dominioRepository.save(DominioEntity.builder()
                .codDominio("11111111111")
                .ragioneSociale("Comune Uno")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());

        dominioDue = dominioRepository.save(DominioEntity.builder()
                .codDominio("22222222222")
                .ragioneSociale("Comune Due")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());

        dominioNonAutorizzato = dominioRepository.save(DominioEntity.builder()
                .codDominio("33333333333")
                .ragioneSociale("Comune Non Autorizzato")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());

        tipoVersamentoUno = creaTipoVersamento("TARI", "Tassa sui rifiuti");
        tipoVersamentoNonAutorizzato = creaTipoVersamento("IMU", "Imposta municipale");
    }

    @AfterEach
    void pulisci() {
        utenzaTipoVersamentoRepository.deleteAll();
        utenzaDominioRepository.deleteAll();
        unitaOperativaRepository.deleteAll();
        tipoVersamentoRepository.deleteAll();
        dominioRepository.deleteAll();
        utenzaRepository.deleteAll();
    }

    private TipoVersamentoEntity creaTipoVersamento(String cod, String descrizione) {
        TipoVersamentoEntity tipoVersamento = new TipoVersamentoEntity();
        tipoVersamento.setCodTipoVersamento(cod);
        tipoVersamento.setDescrizione(descrizione);
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
        return tipoVersamentoRepository.save(tipoVersamento);
    }

    private UtenzaEntity creaUtenza(boolean autorizzazioneDominiStar, boolean autorizzazioneTipiVersStar) {
        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(PRINCIPAL);
        utenza.setPrincipalOriginale(PRINCIPAL);
        utenza.setAbilitato(true);
        utenza.setAutorizzazioneDominiStar(autorizzazioneDominiStar);
        utenza.setAutorizzazioneTipiVersStar(autorizzazioneTipiVersStar);
        utenza.setPassword(passwordEncoder.encode(PASSWORD));
        return utenzaRepository.save(utenza);
    }

    @Test
    @DisplayName("GET /v2/profilo con autorizzazione_domini_star e autorizzazione_tipi_vers_star "
            + "restituisce tutti i domini e tutti i tipi pendenza censiti, non solo quelli collegati")
    void getProfiloConStarRestituisceTuttoIlCensito() throws Exception {
        creaUtenza(true, true);

        mockMvc.perform(get("/v2/profilo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.nome").value(PRINCIPAL))
                .andExpect(jsonPath("$.domini.length()").value(3))
                .andExpect(jsonPath("$.domini[?(@.idDominio == '33333333333')]").exists())
                .andExpect(jsonPath("$.tipiPendenza.length()").value(2))
                .andExpect(jsonPath("$.tipiPendenza[?(@.idTipoPendenza == 'IMU')]").exists());
    }

    @Test
    @DisplayName("GET /v2/profilo senza star restituisce solo i domini/tipi pendenza esplicitamente "
            + "collegati all'utenza, risolvendo anche una riga per unita' operativa al dominio padre "
            + "e deduplicando se piu' righe risolvono allo stesso dominio")
    void getProfiloSenzaStarRestituisceSoloElencoEsplicito() throws Exception {
        UtenzaEntity utenza = creaUtenza(false, false);

        UnitaOperativaEntity uo = new UnitaOperativaEntity();
        uo.setCodUo("UO-1");
        uo.setAbilitato(true);
        uo.setDominio(dominioUno);
        uo = unitaOperativaRepository.save(uo);

        UtenzaDominioEntity rigaDiretta = new UtenzaDominioEntity();
        rigaDiretta.setIdUtenza(utenza.getId());
        rigaDiretta.setIdDominio(dominioUno.getId());
        utenzaDominioRepository.save(rigaDiretta);

        UtenzaDominioEntity rigaViaUo = new UtenzaDominioEntity();
        rigaViaUo.setIdUtenza(utenza.getId());
        rigaViaUo.setIdUo(uo.getId());
        utenzaDominioRepository.save(rigaViaUo);

        UtenzaDominioEntity rigaDominioDue = new UtenzaDominioEntity();
        rigaDominioDue.setIdUtenza(utenza.getId());
        rigaDominioDue.setIdDominio(dominioDue.getId());
        utenzaDominioRepository.save(rigaDominioDue);

        UtenzaTipoVersamentoEntity rigaTipoVersamento = new UtenzaTipoVersamentoEntity();
        rigaTipoVersamento.setIdUtenza(utenza.getId());
        rigaTipoVersamento.setIdTipoVersamento(tipoVersamentoUno.getId());
        utenzaTipoVersamentoRepository.save(rigaTipoVersamento);

        mockMvc.perform(get("/v2/profilo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domini.length()").value(2))
                .andExpect(jsonPath("$.domini[?(@.idDominio == '11111111111')]").exists())
                .andExpect(jsonPath("$.domini[?(@.idDominio == '22222222222')]").exists())
                .andExpect(jsonPath("$.domini[?(@.idDominio == '33333333333')]").doesNotExist())
                .andExpect(jsonPath("$.tipiPendenza.length()").value(1))
                .andExpect(jsonPath("$.tipiPendenza[0].idTipoPendenza").value("TARI"));
    }
}
