package com.kilivana.common.exception;

/**
 * A dependency this request needs could not answer, so the request was not processed.
 *
 * <p>503 and not 400, because the caller did nothing wrong and retrying sooner is what a
 * load balancer should be told. Used for the two fail-closed paths in the Google flow: a
 * limiter that cannot answer (so the send budget cannot be enforced) and an onboarding ticket
 * store that cannot answer (so single use cannot be enforced). Both would otherwise have to
 * choose between proceeding without the control and refusing without telling the caller why.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}