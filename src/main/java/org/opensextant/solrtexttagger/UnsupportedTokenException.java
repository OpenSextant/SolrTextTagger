package org.opensextant.solrtexttagger;

/**
 * Indicates that a token provided to the {@link PhraseBuilder} was not supported.
 * Can be used to ignore labels that can not be correctly encoded.
 * @author Rupert Westenthaler
 *
 */
public class UnsupportedTokenException extends IllegalStateException {

    private static final long serialVersionUID = 7021581582507827078L;

    public UnsupportedTokenException(String message){
        this(message,null);
    }
    
    protected UnsupportedTokenException(String message, Throwable cause){
        super(message,cause);
    }
    
}
