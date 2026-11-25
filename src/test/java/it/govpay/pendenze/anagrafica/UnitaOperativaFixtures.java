package it.govpay.pendenze.anagrafica;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.UnitaOperativaRepository;

/** Fixture dell'anagrafica delle unita' operative per i test che creano posizioni debitorie. */
public final class UnitaOperativaFixtures {

    private UnitaOperativaFixtures() {
    }

    /** Salva e restituisce la unita' operativa EC, abilitata, del dominio. */
    public static UnitaOperativaEntity salvaEc(UnitaOperativaRepository repository, DominioEntity dominio) {
        return salva(repository, dominio, UnitaOperativaResolver.COD_UNITA_OPERATIVA_EC, true);
    }

    public static UnitaOperativaEntity salva(UnitaOperativaRepository repository, DominioEntity dominio,
            String codUo, boolean abilitato) {
        UnitaOperativaEntity unitaOperativa = new UnitaOperativaEntity();
        unitaOperativa.setCodUo(codUo);
        unitaOperativa.setAbilitato(abilitato);
        unitaOperativa.setDominio(dominio);
        return repository.save(unitaOperativa);
    }
}
