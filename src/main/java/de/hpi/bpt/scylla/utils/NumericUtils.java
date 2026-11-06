package de.hpi.bpt.scylla.utils;

/**
 * Classe di utilità per gestire operazioni numeriche in modo sicuro
 * evitando valori NaN o infiniti
 */
public class NumericUtils {
    
    /**
     * Verifica se un valore è valido (non NaN e non infinito)
     * @param value il valore da verificare
     * @return true se il valore è valido, false altrimenti
     */
    public static boolean isValidValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
    
    /**
     * Ottiene un valore sicuro, sostituendo NaN e infinito con un valore predefinito
     * @param value il valore da verificare
     * @param defaultValue il valore predefinito da usare in caso di NaN o infinito
     * @return il valore originale se valido, altrimenti il valore predefinito
     */
    public static double getSafeValue(double value, double defaultValue) {
        return isValidValue(value) ? value : defaultValue;
    }
    
    /**
     * Ottiene un valore sicuro, sostituendo NaN e infinito con 0
     * @param value il valore da verificare
     * @return il valore originale se valido, altrimenti 0
     */
    public static double getSafeValue(double value) {
        return getSafeValue(value, 0.0);
    }
    
    /**
     * Esegue una divisione sicura, evitando divisioni per zero
     * @param numerator il numeratore
     * @param denominator il denominatore
     * @param defaultValue il valore predefinito da usare in caso di divisione per zero
     * @return il risultato della divisione o il valore predefinito
     */
    public static double safeDivide(double numerator, double denominator, double defaultValue) {
        if (!isValidValue(numerator) || !isValidValue(denominator) || denominator == 0) {
            return defaultValue;
        }
        return numerator / denominator;
    }
    
    /**
     * Esegue una divisione sicura, evitando divisioni per zero (usa 0 come valore predefinito)
     * @param numerator il numeratore
     * @param denominator il denominatore
     * @return il risultato della divisione o 0
     */
    public static double safeDivide(double numerator, double denominator) {
        return safeDivide(numerator, denominator, 0.0);
    }
}