package it.govpay.pendenze;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.ConfigurazioneEntity;
import it.govpay.common.entity.ConnettoreEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.DominioLogoEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.entity.TipoTributoEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioLogoRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;

/**
 * Repository scan esteso a {@code it.govpay.common.repository}, ma con
 * {@code excludeFilters}: questo servizio usa {@link ApplicazioneRepository}
 * (risoluzione {@code idA2A}), {@link DominioRepository} (risoluzione dominio e
 * generazione IUV standard, {@code GeneratoreIuvStandard} di govpay-common-pendenze) e
 * {@link DominioLogoRepository} (logo del dominio per l'avviso PDF, vedi
 * {@code AvvisoPdfPayloadMapper}) — i repository di common per cui questo servizio non ha
 * un'entita' corrispondente nella {@link PersistenceManagedTypes} (intermediari/stazioni)
 * restano esclusi: se attivi, il bootstrap fallirebbe non trovando le loro entity nel
 * contesto di persistenza (stesso principio gia' applicato in
 * {@code GovPayConsoleApplication}, dove pero' l'esclusione va nella direzione opposta: qui
 * vogliamo <b>includere</b> Applicazione/Dominio, la' venivano escluse perche' quel servizio
 * ha proprie entita' CRUD equivalenti). {@code ConnettoreEntityRepository}/
 * {@code ConfigurazioneRepository} sono invece attivi: li usano rispettivamente
 * {@code ConnettoreService} e {@code ConfigurazioneService} di govpay-common, entrambi
 * prerequisiti dei log dinamici e delle chiamate al GDE.
 */
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableJpaRepositories(basePackages = { "it.govpay.pendenze.repository", "it.govpay.pendenze.security",
        "it.govpay.common.repository" },
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = { IntermediarioRepository.class, StazioneRepository.class }))
public class GovPayPendenzeApiApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(GovPayPendenzeApiApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(GovPayPendenzeApiApplication.class, args);
    }

    /**
     * Sostituisce {@code @EntityScan(basePackages = {"it.govpay.pendenze.entity",
     * "it.govpay.common.entity"})}: quest'ultimo trascinerebbe dentro anche
     * {@code it.govpay.common.entity.batch} (entity Spring Batch, mai lette da nessun
     * repository attivo qui, ma che Hibernate pretenderebbe comunque di validare contro
     * lo schema) — stesso problema gia' risolto con questa tecnica in
     * {@code GovPayConsoleApplication}. Le entity proprie di
     * {@code it.govpay.pendenze.entity} restano scoperte dinamicamente (scan reale); quelle
     * di govpay-common realmente usate sono aggiunte per nome — non solo
     * {@link ApplicazioneEntity}/{@link DominioEntity}: anche entita' mai lette da nessun
     * repository attivo qui servono comunque, perche' Hibernate valida tutto all'avvio, non
     * solo cio' che viene effettivamente letto a runtime:
     * <ul>
     * <li>{@link StazioneEntity}/{@link IntermediarioEntity}: {@code DominioEntity.stazione}
     * le referenzia via relazione JPA;</li>
     * <li>{@link ConnettoreEntity}: {@code ApplicazioneRepository.findConnettoreIntegrazione}
     * la referenzia nella propria query JPQL — Spring Data valida la query di OGNI metodo
     * del repository alla creazione del bean, non solo di quelli effettivamente chiamati.</li>
     * <li>{@link TipoVersamentoEntity}/{@link TipoVersamentoDominioEntity}/
     * {@link UnitaOperativaEntity}: usate direttamente da {@code PosizioneDebitoriaMapper}
     * per risolvere {@code idTipoPendenza}/{@code idUnitaOperativa} — entita' a piena
     * fedelta' di govpay-common (govpay-common#9), non piu' proprie entita' "slim" di
     * questa libreria.</li>
     * <li>{@link TipoTributoEntity}/{@link TributoEntity}/{@link IbanAccreditoEntity}: usate
     * da {@code PosizioneDebitoriaMapper} per risolvere {@code VocePendenza.idTributo}/
     * {@code idIbanAccredito}/{@code idIbanAppoggio} — {@code codEntrata}/gli IBAN della
     * richiesta sono codici di anagrafica censita, non stringhe libere (vedi Javadoc di
     * classe di {@code VocePendenza}).</li>
     * <li>{@link ConfigurazioneEntity}: {@code ConfigurazioneService}/{@code ConnettoreService}
     * di govpay-common (log dinamici, connettore GDE) la leggono/scrivono direttamente.</li>
     * <li>{@link DominioLogoEntity}: usata da {@code AvvisoPdfPayloadMapper} per leggere il
     * logo del dominio per l'avviso PDF — entity "slim" dedicata (stessa tabella
     * {@code domini} di {@link DominioEntity}, solo {@code cod_dominio}/{@code logo}) per non
     * caricare il BLOB su ogni lettura di {@link DominioEntity}, stesso pattern gia' in uso
     * in govpay-portal-api.</li>
     * </ul>
     */
    @Bean
    public PersistenceManagedTypes persistenceManagedTypes(ResourceLoader resourceLoader) {
        PersistenceManagedTypes proprie = new PersistenceManagedTypesScanner(resourceLoader)
                .scan("it.govpay.pendenze.entity", "it.govpay.pendenze.security");

        List<String> nomiClassi = new ArrayList<>(proprie.getManagedClassNames());
        nomiClassi.add(ApplicazioneEntity.class.getName());
        nomiClassi.add(ConfigurazioneEntity.class.getName());
        nomiClassi.add(DominioEntity.class.getName());
        nomiClassi.add(DominioLogoEntity.class.getName());
        nomiClassi.add(StazioneEntity.class.getName());
        nomiClassi.add(IntermediarioEntity.class.getName());
        nomiClassi.add(ConnettoreEntity.class.getName());
        nomiClassi.add(TipoVersamentoEntity.class.getName());
        nomiClassi.add(TipoVersamentoDominioEntity.class.getName());
        nomiClassi.add(UnitaOperativaEntity.class.getName());
        nomiClassi.add(TipoTributoEntity.class.getName());
        nomiClassi.add(TributoEntity.class.getName());
        nomiClassi.add(IbanAccreditoEntity.class.getName());

        return PersistenceManagedTypes.of(nomiClassi, List.of("it.govpay.pendenze.entity", "it.govpay.pendenze.security"));
    }

    /**
     * Registra il modulo per serializzare {@code JsonNullable} (generato dagli schemi
     * OpenAPI con {@code nullable: true}) in modo trasparente: i campi "undefined" sono
     * omessi, i campi {@code of(null)} sono serializzati come null espliciti, i campi
     * {@code of(value)} come il loro valore.
     */
    @Bean
    public JsonNullableModule jsonNullableModule() {
        return new JsonNullableModule();
    }

    /**
     * Rifiuta (400) un numero JSON con virgola dove lo schema richiede un intero (es.
     * {@code giorni}), invece di troncarlo silenziosamente: Jackson 3 di default TRONCA un
     * valore come {@code 1.9} a {@code 1} deserializzandolo su un campo {@code Integer} —
     * cambiare lo schema OpenAPI da {@code number} a {@code integer} non basta da solo a
     * farlo rifiutare, serve questa configurazione esplicita della coercizione.
     */
    @Bean
    public JsonMapperBuilderCustomizer rifiutaTroncamentoDecimaliSuInteriCustomizer() {
        return builder -> builder.withCoercionConfig(LogicalType.Integer,
                config -> config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }

    /**
     * Orologio di sistema, iniettato dove serve "adesso" a livello di questo servizio
     * (distinto dal {@code pendenzeClock} di govpay-common-pendenze, che governa gli
     * istanti persistiti dalla libreria). Sostituibile nei test per fissare il tempo.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
