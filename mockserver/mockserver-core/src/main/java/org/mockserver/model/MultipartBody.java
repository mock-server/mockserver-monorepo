package org.mockserver.model;

import java.util.List;
import java.util.Objects;

/**
 * Matches a {@code multipart/form-data} request body at the field level.
 * <p>
 * A multipart body is treated as a set of named parts. Each part has a field
 * name and a value, and file parts additionally carry a filename and a
 * part-level content type. This body lets an expectation match on:
 * <ul>
 *     <li>{@code fields} — field name to value patterns (text part value or file part bytes as string)</li>
 *     <li>{@code filenames} — field name to filename patterns (file parts only)</li>
 *     <li>{@code partContentTypes} — field name to part content-type patterns</li>
 * </ul>
 * Each map mirrors the {@link ParameterBody} / form-parameter matching UX:
 * keys and values are {@link NottableString}s, so regular expressions and
 * negation (presence / absence) work exactly as they do for form parameters.
 * Matching uses sub-set semantics — only the specified parts are checked and
 * extra parts in the request are ignored.
 *
 * @author jamesdbloom
 */
public class MultipartBody extends Body<Parameters> {
    private int hashCode;
    private Parameters fields = new Parameters();
    private Parameters filenames = new Parameters();
    private Parameters partContentTypes = new Parameters();

    public MultipartBody(Parameter... fields) {
        this(new Parameters().withEntries(fields), null, null);
    }

    public MultipartBody(List<Parameter> fields) {
        this(new Parameters().withEntries(fields), null, null);
    }

    public MultipartBody(Parameters fields) {
        this(fields, null, null);
    }

    public MultipartBody(Parameters fields, Parameters filenames, Parameters partContentTypes) {
        super(Type.MULTIPART);
        if (fields != null) {
            this.fields = fields;
        }
        if (filenames != null) {
            this.filenames = filenames;
        }
        if (partContentTypes != null) {
            this.partContentTypes = partContentTypes;
        }
    }

    public static MultipartBody multipart(Parameter... fields) {
        return new MultipartBody(fields);
    }

    public static MultipartBody multipart(List<Parameter> fields) {
        return new MultipartBody(fields);
    }

    public static MultipartBody multipart(Parameters fields) {
        return new MultipartBody(fields);
    }

    public MultipartBody withFilenames(Parameters filenames) {
        if (filenames != null) {
            this.filenames = filenames;
        }
        this.hashCode = 0;
        return this;
    }

    public MultipartBody withFilenames(Parameter... filenames) {
        return withFilenames(new Parameters().withEntries(filenames));
    }

    public MultipartBody withPartContentTypes(Parameters partContentTypes) {
        if (partContentTypes != null) {
            this.partContentTypes = partContentTypes;
        }
        this.hashCode = 0;
        return this;
    }

    public MultipartBody withPartContentTypes(Parameter... partContentTypes) {
        return withPartContentTypes(new Parameters().withEntries(partContentTypes));
    }

    @Override
    public Parameters getValue() {
        return this.fields;
    }

    public Parameters getFields() {
        return this.fields;
    }

    public Parameters getFilenames() {
        return this.filenames;
    }

    public Parameters getPartContentTypes() {
        return this.partContentTypes;
    }

    @Override
    public MultipartBody copy() {
        return withFlagsCopiedTo(new MultipartBody(fields.deepCopy(), filenames.deepCopy(), partContentTypes.deepCopy()));
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
        MultipartBody that = (MultipartBody) o;
        return Objects.equals(fields, that.fields) &&
            Objects.equals(filenames, that.filenames) &&
            Objects.equals(partContentTypes, that.partContentTypes);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), fields, filenames, partContentTypes);
        }
        return hashCode;
    }
}
