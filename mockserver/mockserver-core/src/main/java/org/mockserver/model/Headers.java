package org.mockserver.model;

import com.google.common.collect.Multimap;

import java.util.Collection;
import java.util.List;

/**
 * @author jamesdbloom
 */
public class Headers extends KeysToMultiValues<Header, Headers> {

    public Headers(List<Header> headers) {
        withEntries(headers);
    }

    public Headers(Header... headers) {
        withEntries(headers);
    }

    public Headers(Multimap<NottableString, NottableString> headers) {
        super(headers);
    }

    public static Headers headers(Header... headers) {
        return new Headers(headers);
    }

    @Override
    public Header build(NottableString name, Collection<NottableString> values) {
        return new Header(name, values);
    }

    protected void isModified() {
        super.isModified();
    }

    public Headers withKeyMatchStyle(KeyMatchStyle keyMatchStyle) {
        super.withKeyMatchStyle(keyMatchStyle);
        return this;
    }

    public Headers clone() {
        return new Headers(getMultimap());
    }

    /**
     * A copy sharing no mutable state, which unlike {@link #clone()} keeps the key match style.
     */
    Headers deepCopy() {
        return copyEntriesInto(new Headers());
    }

}
