package it.govpay.pendenze.anagrafica;

import org.springframework.stereotype.Component;

import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.web.AnagraficaNonTrovataException;

/**
 * Risolve l'unita' operativa di una posizione debitoria dal codice indicato dal client.
 *
 * <ul>
 * <li>{@link #risolviPerCreazione}: il codice omesso vale {@value #COD_UNITA_OPERATIVA_EC};
 * restituisce sempre l'id di una unita' operativa del dominio, esistente e abilitata.</li>
 * <li>{@link #risolvi}: il codice omesso resta {@code null}, senza accedere all'anagrafica.</li>
 * <li>{@link #normalizzaEc}: l'id della unita' operativa {@value #COD_UNITA_OPERATIVA_EC}
 * diventa {@code null}, per confrontare unita' operative gia' salvate.</li>
 * </ul>
 */
@Component
public class UnitaOperativaResolver {

    public static final String COD_UNITA_OPERATIVA_EC = "EC";

    private final UnitaOperativaRepository unitaOperativaRepository;

    public UnitaOperativaResolver(UnitaOperativaRepository unitaOperativaRepository) {
        this.unitaOperativaRepository = unitaOperativaRepository;
    }

    /**
     * @throws AnagraficaNonTrovataException se l'unita' operativa (anche {@value #COD_UNITA_OPERATIVA_EC}
     *                                        se omessa) non esiste per il dominio
     * @throws ValidazioneNonSuperataException se l'unita' operativa e' disabilitata
     */
    public Long risolviPerCreazione(Long idDominio, String idUnitaOperativa) {
        return risolvi(idDominio, idUnitaOperativa == null ? COD_UNITA_OPERATIVA_EC : idUnitaOperativa);
    }

    /**
     * @return {@code null} se {@code idUnitaOperativa} e' {@code null}
     * @throws AnagraficaNonTrovataException se l'unita' operativa non esiste per il dominio
     * @throws ValidazioneNonSuperataException se l'unita' operativa e' disabilitata
     */
    public Long risolvi(Long idDominio, String idUnitaOperativa) {
        if (idUnitaOperativa == null) {
            return null;
        }
        UnitaOperativaEntity unitaOperativa = unitaOperativaRepository
                .findByCodUoAndDominioId(idUnitaOperativa, idDominio)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessuna unita' operativa con idUnitaOperativa ["
                        + idUnitaOperativa + "] per il dominio [id:" + idDominio + "]"));
        if (Boolean.FALSE.equals(unitaOperativa.getAbilitato())) {
            throw new ValidazioneNonSuperataException("l'unita' operativa [" + idUnitaOperativa
                    + "] per il dominio [id:" + idDominio + "] non e' abilitata");
        }
        return unitaOperativa.getId();
    }

    /** @return {@code null} se {@code idUnitaOperativa} e' {@code null} o e' quella con codice EC, altrimenti {@code idUnitaOperativa} */
    public Long normalizzaEc(Long idUnitaOperativa) {
        if (idUnitaOperativa == null) {
            return null;
        }
        boolean ec = unitaOperativaRepository.findById(idUnitaOperativa).map(UnitaOperativaEntity::getCodUo)
                .map(COD_UNITA_OPERATIVA_EC::equals).orElse(false);
        return ec ? null : idUnitaOperativa;
    }
}
