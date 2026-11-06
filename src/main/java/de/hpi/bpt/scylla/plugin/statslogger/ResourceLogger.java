package de.hpi.bpt.scylla.plugin.statslogger;

import de.hpi.bpt.scylla.utils.NumericUtils;

/**
 * Classe per il logging delle risorse
 */
public class ResourceLogger {

    // Metodi per il calcolo sicuro dell'utilizzo delle risorse
    
    /**
     * Calcola l'utilizzo delle risorse in modo sicuro
     * @param used quantità di risorsa utilizzata
     * @param total quantità totale di risorsa disponibile
     * @return percentuale di utilizzo o 0 se i valori non sono validi
     */
    public double calculateResourceUtilization(double used, double total) {
        return NumericUtils.safeDivide(used, total);
    }
    
    /**
     * Calcola la durata in modo sicuro
     * @param endTime tempo di fine
     * @param startTime tempo di inizio
     * @return durata calcolata o 0 se i tempi non sono validi
     */
    public long calculateDuration(long endTime, long startTime) {
        return (long)NumericUtils.getSafeValue(endTime - startTime);
    }
    
    /**
     * Ottiene un valore sicuro per un attributo di risorsa
     * @param attributeValue il valore dell'attributo
     * @return il valore sicuro (0 se NaN o infinito)
     */
    public double getSafeResourceValue(double attributeValue) {
        return NumericUtils.getSafeValue(attributeValue);
    }
    
    /**
     * Verifica se un attributo di risorsa è presente
     * @param attributeValue il valore dell'attributo
     * @return true se l'attributo è presente e valido
     */
    public boolean hasValidResourceAttribute(Object attributeValue) {
        if (attributeValue == null) return false;
        
        if (attributeValue instanceof Number) {
            double value = ((Number)attributeValue).doubleValue();
            return NumericUtils.isValidValue(value);
        }
        
        return false;
    }
}