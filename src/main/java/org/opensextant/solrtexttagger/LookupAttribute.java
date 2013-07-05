package org.opensextant.solrtexttagger;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.util.Attribute;

/**
 * Attribute used by the {@link Tagger} to decide if it needs to lookup a
 * Token in the {@link TaggerFstCorpus} or not.<p>
 * 
 * By default this Attribute will return <code>true</code>, but it might be
 * reset by some {@link TokenFilter} added to the {@link TokenStream} used
 * to analyze the parsed text. Typically this will be done based on NLP
 * processing results (e.g. to only lookup Named Entities).
 *  
 * @author Rupert Westenthaler
 *
 */
public interface LookupAttribute extends Attribute {

    /**
     * By default this Attribute will be initialised with <code>true</code>.
     * This ensures lookups for all tokens if this Attribute is not
     * supported by the {@link TokenStream}  
     */
    public static final boolean DEFAULT_LOOKUP = true;
    
    /**
     * Getter for the lookup state
     * @return the state
     */
    public boolean isLookup();
    /**
     * Setter for the lookup attribute. Typically called by code within
     * {@link TokenFilter#incrementToken()}.
     * @param lookup the state
     */
    public void setLookup(boolean lookup);
    
}
