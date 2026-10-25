package org.mockserver.model;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * @author jamesdbloom
 */
public class ParameterBody extends Body<Parameters> {
    private int hashCode;
    private Parameters parameters = new Parameters();

    public ParameterBody(Parameter... parameters) {
        this(new Parameters().withEntries(parameters));
    }

    public ParameterBody(List<Parameter> parameters) {
        this(new Parameters().withEntries(parameters));
    }

    public ParameterBody(Parameters parameters) {
        super(Type.PARAMETERS);
        if (parameters != null) {
            this.parameters = parameters;
        }
    }

    public static ParameterBody params(Parameters parameters) {
        return new ParameterBody(parameters);
    }

    public static ParameterBody params(Parameter... parameters) {
        return new ParameterBody(parameters);
    }

    public static ParameterBody params(List<Parameter> parameters) {
        return new ParameterBody(parameters);
    }

    public static ParameterBody parameterBody(Parameters parameters) {
        return new ParameterBody(parameters);
    }

    public static ParameterBody parameterBody(Parameter... parameters) {
        return new ParameterBody(parameters);
    }

    public static ParameterBody parameterBody(List<Parameter> parameters) {
        return new ParameterBody(parameters);
    }

    public Parameters getValue() {
        return this.parameters;
    }

    @Override
    public String toString() {
        StringBuilder body = new StringBuilder();
        List<Parameter> bodyParameters = this.parameters.getEntries();
        for (int i = 0; i < bodyParameters.size(); i++) {
            Parameter parameter = bodyParameters.get(i);
            if (parameter.getValues().isEmpty()) {
                body.append(parameter.getName().getValue());
                body.append('=');
            } else {
                List<NottableString> values = parameter.getValues();
                for (int j = 0; j < values.size(); j++) {
                    String value = values.get(j).getValue();
                    body.append(parameter.getName().getValue());
                    body.append('=');
                    try {
                        body.append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
                    } catch (UnsupportedEncodingException uee) {
                        throw new RuntimeException("UnsupportedEncodingException while encoding body parameters for " + parameters, uee);
                    }
                    if (j < (values.size() - 1)) {
                        body.append('&');
                    }
                }
            }
            if (i < (bodyParameters.size() - 1)) {
                body.append('&');
            }
        }
        return body.toString();
    }

    @Override
    public ParameterBody copy() {
        return withFlagsCopiedTo(new ParameterBody(parameters.deepCopy()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        ParameterBody that = (ParameterBody) o;
        return Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), parameters);
        }
        return hashCode;
    }
}
