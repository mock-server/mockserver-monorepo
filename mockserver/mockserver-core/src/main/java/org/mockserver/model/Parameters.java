package org.mockserver.model;

import com.google.common.collect.Multimap;

import java.util.Collection;
import java.util.List;

/**
 * @author jamesdbloom
 */
public class Parameters extends KeysToMultiValues<Parameter, Parameters> {

    private String rawParameterString;

    public Parameters(List<Parameter> parameters) {
        withEntries(parameters);
    }

    public Parameters(Parameter... parameters) {
        withEntries(parameters);
    }

    public Parameters(Multimap<NottableString, NottableString> headers) {
        super(headers);
    }

    public static Parameters parameters(Parameter... parameters) {
        return new Parameters(parameters);
    }

    @Override
    public Parameter build(NottableString name, Collection<NottableString> values) {
        return new Parameter(name, values);
    }

    protected void isModified() {
        super.isModified();
        rawParameterString = null;
    }

    public Parameters withKeyMatchStyle(KeyMatchStyle keyMatchStyle) {
        super.withKeyMatchStyle(keyMatchStyle);
        return this;
    }

    public String getRawParameterString() {
        return rawParameterString;
    }

    public Parameters withRawParameterString(String rawParameterString) {
        this.rawParameterString = rawParameterString;
        return this;
    }

    public Parameters clone() {
        return new Parameters(getMultimap())
            .withRawParameterString(rawParameterString);
    }

    /**
     * A copy sharing no mutable state, which unlike {@link #clone()} keeps the key match style.
     */
    Parameters deepCopy() {
        return copyEntriesInto(new Parameters()).withRawParameterString(rawParameterString);
    }

}
