package org.opensextant.solrtexttagger;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.util.Attribute;

/**
 * Attribute used by the {@link Tagger} to decide if a token can start a 
 * new {@link TagLL tag}.<p>
 * 
 * By default this Attribute will return <code>true</code>, but it might be
 * reset by some {@link TokenFilter} added to the {@link TokenStream} used
 * to analyze the parsed text. Typically this will be done based on NLP
 * processing results (e.g. to only lookup Named Entities). <p>
 * 
 * NOTE: that all Tokens are used to advance existing {@link TagLL tags}.
 *  
 * @author Rupert Westenthaler
 *
 */
public interface TaggingAttribute extends Attribute {

    /**
     * By default this Attribute will be initialised with <code>true</code>.
     * This ensures that all tokens are taggable by default (especially if
     * the {@link TaggingAttribute} is not set by any component in the configured
     * {@link TokenStream}  
     */
    public static final boolean DEFAULT_LOOKUP = true;
    
    /**
     * Getter for the taggable state of the current Token
     * @return the state
     */
    public boolean isTaggable();
    /**
     * Setter for the taggable state. Typically called by code within
     * {@link TokenFilter#incrementToken()}.
     * @param lookup the state
     */
    public void setTaggable(boolean lookup);
    
}
