package de.hpi.bpt.scylla.plugin.resourcecost;

import de.hpi.bpt.scylla.utils.NumericUtils;

/**
 * Wrapper di sicurezza per il plugin resourceCost
 */
public class ResourceCostSafetyWrapper {
    
    /**
     * Ottiene un valore sicuro per un attributo di risorsa
     * @param attributeValue il valore dell'attributo
     * @param defaultValue il valore predefinito da usare se l'attributo non è valido
     * @return il valore sicuro
     */
    public static double getSafeAttributeValue(Object attributeValue, double defaultValue) {
        if (attributeValue == null) return defaultValue;
        
        if (attributeValue instanceof Number) {
            double value = ((Number)attributeValue).doubleValue();
            return NumericUtils.getSafeValue(value, defaultValue);
        }
        
        try {
            return NumericUtils.getSafeValue(Double.parseDouble(attributeValue.toString()), defaultValue);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * Calcola l'utilizzo delle risorse in modo sicuro
     * @param used quantità di risorsa utilizzata
     * @param total quantità totale di risorsa disponibile
     * @return percentuale di utilizzo o 0 se i valori non sono validi
     */
    public static double calculateResourceUtilization(double used, double total) {
        return NumericUtils.safeDivide(used, total);
    }
    
    /**
     * Calcola la durata in modo sicuro
     * @param endTime tempo di fine
     * @param startTime tempo di inizio
     * @return durata calcolata o 0 se i tempi non sono validi
     */
    public static long calculateDuration(long endTime, long startTime) {
        if (endTime < 0 || startTime < 0) return 0;
        return (long)NumericUtils.getSafeValue(endTime - startTime);
    }
}