package org.opensextant.solrtexttagger;

import org.apache.lucene.util.AttributeImpl;

/**
 * Implementation of the {@link LookupAttribute}
 *  
 * @author Rupert Westenthaler
 *
 */
public class LookupAttributeImpl extends AttributeImpl implements LookupAttribute {

    /**
     * the private field initialised with {@link LookupAttribute#DEFAULT_LOOKUP}
     */
    private boolean lookup = LookupAttribute.DEFAULT_LOOKUP;
    
    /*
     * (non-Javadoc)
     * @see org.opensextant.solrtexttagger.LookupAttribute#isLookup()
     */
    @Override
    public boolean isLookup() {
        return lookup;
    }
    /*
     * (non-Javadoc)
     * @see org.opensextant.solrtexttagger.LookupAttribute#setLookup(boolean)
     */
    @Override
    public void setLookup(boolean lookup) {
        this.lookup = lookup;
    }
    /*
     * (non-Javadoc)
     * @see org.apache.lucene.util.AttributeImpl#clear()
     */
    @Override
    public void clear() {
        lookup = DEFAULT_LOOKUP;
        
    }
    /*
     * (non-Javadoc)
     * @see org.apache.lucene.util.AttributeImpl#copyTo(org.apache.lucene.util.AttributeImpl)
     */
    @Override
    public void copyTo(AttributeImpl target) {
        ((LookupAttribute)target).setLookup(lookup);
    }
    
}
