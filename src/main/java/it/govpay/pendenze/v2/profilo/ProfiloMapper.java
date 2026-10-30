package it.govpay.pendenze.v2.profilo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.TipoVersamentoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.security.UtenzaDominioEntity;
import it.govpay.pendenze.security.UtenzaDominioRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaTipoVersamentoEntity;
import it.govpay.pendenze.security.UtenzaTipoVersamentoRepository;
import it.govpay.pendenze.v2.api.model.Dominio;
import it.govpay.pendenze.v2.api.model.Profilo;
import it.govpay.pendenze.v2.api.model.TipoPendenza;

/**
 * Costruisce lo schema {@code Profilo} dello YAML v2 — replica
 * {@code it.govpay.pendenze.v2.beans.converter.ProfiloConverter} del legacy monorepo
 * (modulo {@code wars/api-pendenze}), basato su {@code UtentiDAO#getProfilo}:
 * {@code nome} = principal dell'utenza, {@code domini} e {@code tipiPendenza} secondo lo
 * stesso meccanismo "star oppure elenco esplicito" gia' usato per l'autorizzazione al tipo
 * versamento in fase di creazione pendenza (vedi Javadoc di
 * {@code PosizioneDebitoriaMapper#verificaAutorizzazioneTipoVersamento}), qui applicato anche
 * ai domini (colonna {@code utenze.autorizzazione_domini_star} e tabella
 * {@code utenze_domini}, mai letti altrove in questo servizio: v3 non applica alcuna
 * restrizione per dominio in fase di creazione/lettura pendenza — {@code ApplicazioneEntity}
 * non ha nessuna colonna di scoping sul dominio — ma il dato di infrastruttura condivisa
 * esiste comunque nella tabella {@code utenze}/{@code utenze_domini}, popolata dalla stessa
 * migrazione del legacy, ed e' questo endpoint a doverlo riportare fedelmente).
 *
 * <p>Una riga di {@code utenze_domini} ha {@code idDominio} oppure {@code idUo} (mai
 * entrambi): quando e' una UO il dominio e' quello del padre
 * ({@link UnitaOperativaEntity#getDominio()}) — stessa risoluzione di
 * {@code UtentiDAO#convertIdUnitaOperativeToDomini} nel legacy, qui semplificata perche' lo
 * schema v2 {@code Dominio} non espone la UO, solo {@code idDominio}/{@code ragioneSociale}:
 * basta deduplicare per dominio risolto.</p>
 *
 * <p>Il legacy non filtra per {@code abilitato} in nessuno dei due rami (ne' sui domini ne'
 * sui tipi versamento, vedi {@code DominioFilter}/{@code TipoVersamentoFilter} senza criterio
 * su quella colonna): stessa fedelta' qui, nessun filtro aggiunto.</p>
 *
 * <p>{@code acl}, {@code anagrafica} e {@code identityData} restano sempre assenti:
 * {@code ProfiloConverter} del legacy non li popola mai, nonostante {@code acl} sia
 * {@code required} nello YAML — bug/scollegamento del legacy, replicato per fedelta'.</p>
 */
@Component
public class ProfiloMapper {

    private final DominioRepository dominioRepository;
    private final UnitaOperativaRepository unitaOperativaRepository;
    private final UtenzaDominioRepository utenzaDominioRepository;
    private final TipoVersamentoRepository tipoVersamentoRepository;
    private final UtenzaTipoVersamentoRepository utenzaTipoVersamentoRepository;

    public ProfiloMapper(DominioRepository dominioRepository, UnitaOperativaRepository unitaOperativaRepository,
            UtenzaDominioRepository utenzaDominioRepository, TipoVersamentoRepository tipoVersamentoRepository,
            UtenzaTipoVersamentoRepository utenzaTipoVersamentoRepository) {
        this.dominioRepository = dominioRepository;
        this.unitaOperativaRepository = unitaOperativaRepository;
        this.utenzaDominioRepository = utenzaDominioRepository;
        this.tipoVersamentoRepository = tipoVersamentoRepository;
        this.utenzaTipoVersamentoRepository = utenzaTipoVersamentoRepository;
    }

    public Profilo costruisciProfilo(UtenzaEntity utenza) {
        Profilo profilo = new Profilo();
        profilo.setNome(utenza.getPrincipal());
        profilo.setDomini(risolviDomini(utenza));
        profilo.setTipiPendenza(risolviTipiPendenza(utenza));
        return profilo;
    }

    private List<Dominio> risolviDomini(UtenzaEntity utenza) {
        if (Boolean.TRUE.equals(utenza.getAutorizzazioneDominiStar())) {
            return dominioRepository.findAll().stream().map(ProfiloMapper::toDominioRsModel).toList();
        }
        Map<Long, DominioEntity> dominiPerId = new LinkedHashMap<>();
        for (UtenzaDominioEntity riga : utenzaDominioRepository.findByIdUtenza(utenza.getId())) {
            risolviDominioDellaRiga(riga).ifPresent(dominio -> dominiPerId.putIfAbsent(dominio.getId(), dominio));
        }
        return dominiPerId.values().stream().map(ProfiloMapper::toDominioRsModel).toList();
    }

    private Optional<DominioEntity> risolviDominioDellaRiga(UtenzaDominioEntity riga) {
        if (riga.getIdDominio() != null) {
            return dominioRepository.findById(riga.getIdDominio());
        }
        if (riga.getIdUo() != null) {
            return unitaOperativaRepository.findById(riga.getIdUo()).map(UnitaOperativaEntity::getDominio);
        }
        return Optional.empty();
    }

    private static Dominio toDominioRsModel(DominioEntity dominio) {
        return new Dominio().idDominio(dominio.getCodDominio()).ragioneSociale(dominio.getRagioneSociale());
    }

    private List<TipoPendenza> risolviTipiPendenza(UtenzaEntity utenza) {
        if (Boolean.TRUE.equals(utenza.getAutorizzazioneTipiVersStar())) {
            return tipoVersamentoRepository.findAll().stream().map(ProfiloMapper::toTipoPendenzaRsModel).toList();
        }
        return utenzaTipoVersamentoRepository.findByIdUtenza(utenza.getId()).stream()
                .map(UtenzaTipoVersamentoEntity::getIdTipoVersamento)
                .map(tipoVersamentoRepository::findById)
                .flatMap(Optional::stream)
                .map(ProfiloMapper::toTipoPendenzaRsModel)
                .toList();
    }

    private static TipoPendenza toTipoPendenzaRsModel(TipoVersamentoEntity tipoVersamento) {
        return new TipoPendenza().idTipoPendenza(tipoVersamento.getCodTipoVersamento())
                .descrizione(tipoVersamento.getDescrizione());
    }
}
