package org.mockserver.model;

import java.util.Objects;

/**
 * @author jamesdbloom
 */
public class RegexBody extends Body<String> {
    private int hashCode;
    private final String regex;

    public RegexBody(String regex) {
        super(Type.REGEX);
        this.regex = regex;
    }

    public String getValue() {
        return regex;
    }

    public static RegexBody regex(String regex) {
        return new RegexBody(regex);
    }

    @Override
    public RegexBody copy() {
        return withFlagsCopiedTo(new RegexBody(regex));
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
        RegexBody regexBody = (RegexBody) o;
        return Objects.equals(regex, regexBody.regex);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), regex);
        }
        return hashCode;
    }
}
