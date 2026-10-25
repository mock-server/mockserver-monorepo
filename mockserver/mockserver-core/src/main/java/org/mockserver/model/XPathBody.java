package org.mockserver.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * @author jamesdbloom
 */
public class XPathBody extends Body<String> {
    private int hashCode;
    private final String xpath;
    private final Map<String, String> namespacePrefixes;
    private final NottableDateTimeString dateTime;

    public XPathBody(String xpath) {
      this(xpath, null, null);
    }
    
    public XPathBody(String xpath, Map<String, String> namespacePrefixes) {
      this(xpath, namespacePrefixes, null);
    }

    /**
     * @param dateTime when not null, the body matches only when the text of a node the XPath selects is a
     *                 date-time that satisfies this comparison
     */
    public XPathBody(String xpath, Map<String, String> namespacePrefixes, NottableDateTimeString dateTime) {
      super(Type.XPATH);
      this.xpath = xpath;
      this.namespacePrefixes = namespacePrefixes;
      this.dateTime = dateTime;
    }

    public String getValue() {
        return xpath;
    }
    public Map<String, String> getNamespacePrefixes() {
        return namespacePrefixes;
    }

    public NottableDateTimeString getDateTime() {
        return dateTime;
    }

    public static XPathBody xpath(String xpath) {
       return new XPathBody(xpath);
    }
    public static XPathBody xpath(String xpath, Map<String, String> namespacePrefixes) {
        return new XPathBody(xpath, namespacePrefixes);
    }

    /**
     * Matches when the text of a node the XPath selects is a date-time that satisfies {@code dateTime}, for
     * example {@code xpath("/order/created", after("now -1 days"))}.
     */
    public static XPathBody xpath(String xpath, NottableDateTimeString dateTime) {
        return new XPathBody(xpath, null, dateTime);
    }

    public static XPathBody xpath(String xpath, Map<String, String> namespacePrefixes, NottableDateTimeString dateTime) {
        return new XPathBody(xpath, namespacePrefixes, dateTime);
    }

    @Override
    public XPathBody copy() {
        return withFlagsCopiedTo(new XPathBody(xpath, namespacePrefixes != null ? new LinkedHashMap<>(namespacePrefixes) : null, dateTime));
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
        XPathBody xPathBody = (XPathBody) o;
        return Objects.equals(xpath, xPathBody.xpath) && Objects.equals(namespacePrefixes, xPathBody.namespacePrefixes) && Objects.equals(dateTime, xPathBody.dateTime);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
          hashCode = dateTime == null ? Objects.hash(super.hashCode(), xpath, namespacePrefixes) : Objects.hash(super.hashCode(), xpath, namespacePrefixes, dateTime);
        }
        return hashCode;
    }
}
