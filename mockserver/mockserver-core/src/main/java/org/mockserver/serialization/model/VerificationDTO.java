package org.mockserver.serialization.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.mockserver.model.ExpectationId;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.ObjectWithJsonToString;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.serialization.deserializers.body.VerificationHttpResponseDTODeserializer;
import org.mockserver.verify.Disposition;
import org.mockserver.verify.Verification;

import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationTimes.once;

/**
 * @author jamesdbloom
 */
public class VerificationDTO extends ObjectWithJsonToString implements DTO<Verification> {
    private RequestDefinitionDTO httpRequest;
    private HttpResponseDTO httpResponse;
    private ExpectationId expectationId;
    private VerificationTimesDTO times;
    private Integer maximumNumberOfRequestToReturnInVerificationFailure;
    private Disposition disposition;
    private Long timeout;

    public VerificationDTO(Verification verification) {
        if (verification != null) {
            if (verification.getHttpRequest() instanceof HttpRequest) {
                httpRequest = new HttpRequestDTO((HttpRequest) verification.getHttpRequest());
            } else if (verification.getHttpRequest() instanceof OpenAPIDefinition) {
                httpRequest = new OpenAPIDefinitionDTO((OpenAPIDefinition) verification.getHttpRequest());
            }
            httpResponse = verification.getHttpResponse() != null ? new HttpResponseDTO(verification.getHttpResponse()) : null;
            expectationId = verification.getExpectationId();
            times = new VerificationTimesDTO(verification.getTimes());
            maximumNumberOfRequestToReturnInVerificationFailure = verification.getMaximumNumberOfRequestToReturnInVerificationFailure();
            disposition = verification.getDisposition();
            timeout = verification.getTimeout();
        }
    }

    public VerificationDTO() {
    }

    public Verification buildObject() {
        return verification()
            .withRequest((httpRequest != null ? httpRequest.buildObject() : null))
            .withResponse(httpResponse != null ? httpResponse.buildObject() : null)
            .withExpectationId(expectationId)
            .withTimes((times != null ? times.buildObject() : once()))
            .withMaximumNumberOfRequestToReturnInVerificationFailure(maximumNumberOfRequestToReturnInVerificationFailure)
            .withDisposition(disposition)
            .withTimeout(timeout);
    }

    public RequestDefinitionDTO getHttpRequest() {
        return httpRequest;
    }

    public VerificationDTO setHttpRequest(RequestDefinitionDTO httpRequest) {
        this.httpRequest = httpRequest;
        return this;
    }

    public HttpResponseDTO getHttpResponse() {
        return httpResponse;
    }

    @JsonDeserialize(using = VerificationHttpResponseDTODeserializer.class)
    public VerificationDTO setHttpResponse(HttpResponseDTO httpResponse) {
        this.httpResponse = httpResponse;
        return this;
    }

    public ExpectationId getExpectationId() {
        return expectationId;
    }

    public VerificationDTO setExpectationId(ExpectationId expectationId) {
        this.expectationId = expectationId;
        return this;
    }

    public VerificationTimesDTO getTimes() {
        return times;
    }

    public VerificationDTO setTimes(VerificationTimesDTO times) {
        this.times = times;
        return this;
    }

    public Integer getMaximumNumberOfRequestToReturnInVerificationFailure() {
        return maximumNumberOfRequestToReturnInVerificationFailure;
    }

    public VerificationDTO setMaximumNumberOfRequestToReturnInVerificationFailure(Integer maximumNumberOfRequestToReturnInVerificationFailure) {
        this.maximumNumberOfRequestToReturnInVerificationFailure = maximumNumberOfRequestToReturnInVerificationFailure;
        return this;
    }

    public Disposition getDisposition() {
        return disposition;
    }

    public VerificationDTO setDisposition(Disposition disposition) {
        this.disposition = disposition;
        return this;
    }

    public Long getTimeout() {
        return timeout;
    }

    public VerificationDTO setTimeout(Long timeout) {
        this.timeout = timeout;
        return this;
    }
}
