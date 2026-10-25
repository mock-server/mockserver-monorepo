package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.file.FileReader;

import java.util.Objects;

public class FileBody extends BodyWithContentType<String> {
    private int hashCode;
    private final String filePath;
    private final HttpTemplate.TemplateType templateType;
    private Boolean templatedFilePath;

    public FileBody(String filePath) {
        this(filePath, null);
    }

    public FileBody(String filePath, MediaType contentType) {
        this(filePath, contentType, null);
    }

    public FileBody(String filePath, MediaType contentType, HttpTemplate.TemplateType templateType) {
        super(Type.FILE, contentType);
        this.filePath = filePath;
        this.templateType = templateType;
    }

    public static FileBody file(String filePath) {
        return new FileBody(filePath);
    }

    public static FileBody file(String filePath, MediaType contentType) {
        return new FileBody(filePath, contentType);
    }

    public static FileBody file(String filePath, MediaType contentType, HttpTemplate.TemplateType templateType) {
        return new FileBody(filePath, contentType, templateType);
    }

    public static FileBody file(String filePath, HttpTemplate.TemplateType templateType) {
        return new FileBody(filePath, null, templateType);
    }

    public String getFilePath() {
        return filePath;
    }

    /**
     * When set, the file contents are processed by the named template engine against the request
     * before being returned as the response body. When {@code null} the file is returned verbatim.
     */
    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    /**
     * When {@code true}, and a supported {@link #getTemplateType() templateType} is set, the file PATH is
     * also rendered as a template against the request (for example {@code files/{{ request.pathParameters.id.0 }}.json})
     * before the file is read. Request-derived parts of the rendered path must stay inside the directory
     * given by the path's literal prefix. When {@code null} or {@code false} the path is used literally.
     */
    public Boolean getTemplatedFilePath() {
        return templatedFilePath;
    }

    public FileBody withTemplatedFilePath(Boolean templatedFilePath) {
        this.templatedFilePath = templatedFilePath;
        this.hashCode = 0;
        return this;
    }

    public String getValue() {
        return filePath;
    }

    @Override
    @JsonIgnore
    public byte[] getRawBytes() {
        try {
            String content = FileReader.readFileFromClassPathOrPath(filePath);
            return encodeToRawBytes(content);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    @Override
    public String toString() {
        try {
            return FileReader.readFileFromClassPathOrPath(filePath);
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public FileBody copy() {
        return withFlagsCopiedTo(new FileBody(filePath, contentType, templateType).withTemplatedFilePath(templatedFilePath));
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
        FileBody fileBody = (FileBody) o;
        return Objects.equals(filePath, fileBody.filePath) &&
            templateType == fileBody.templateType &&
            Objects.equals(templatedFilePath, fileBody.templatedFilePath);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), filePath, templateType, templatedFilePath);
        }
        return hashCode;
    }
}
