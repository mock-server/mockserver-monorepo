package org.mockserver.model;

import org.mockserver.file.FileReader;

import java.util.Objects;

/**
 * @author jamesdbloom
 */
public class XmlSchemaBody extends Body<String> {
    private int hashCode;
    private final String xmlSchema;
    private final String sourceUri;

    public XmlSchemaBody(String xmlSchema) {
        this(xmlSchema, null);
    }

    public XmlSchemaBody(String xmlSchema, String sourceUri) {
        super(Type.XML_SCHEMA);
        this.xmlSchema = xmlSchema;
        this.sourceUri = sourceUri;
    }

    public static XmlSchemaBody xmlSchema(String xmlSchema) {
        return new XmlSchemaBody(xmlSchema);
    }

    public static XmlSchemaBody xmlSchemaFromResource(String xmlSchemaPath) {
        String sourceUri = resolveSourceUri(xmlSchemaPath);
        return new XmlSchemaBody(FileReader.readFileFromClassPathOrPath(xmlSchemaPath), sourceUri);
    }

    private static String resolveSourceUri(String path) {
        java.net.URL resource = XmlSchemaBody.class.getClassLoader().getResource(path);
        if (resource != null) {
            return resource.toExternalForm();
        }
        java.io.File file = new java.io.File(path);
        if (file.exists()) {
            return file.toURI().toString();
        }
        return null;
    }

    public String getValue() {
        return xmlSchema;
    }

    public String getSourceUri() {
        return sourceUri;
    }

    @Override
    public XmlSchemaBody copy() {
        return withFlagsCopiedTo(new XmlSchemaBody(xmlSchema, sourceUri));
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
        XmlSchemaBody that = (XmlSchemaBody) o;
        return Objects.equals(xmlSchema, that.xmlSchema);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), xmlSchema);
        }
        return hashCode;
    }
}
