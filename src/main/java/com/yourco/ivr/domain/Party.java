package com.yourco.ivr.domain;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class Party {
    private String partyId;
    private String accountNumber;
    private String dateOfBirth;
    private String ssnLast4;
    private String cardLast4;
    private String zipCode;
    private boolean active;
    private boolean primaryAni;
    private Map<String, String> additionalAttributes;

    public Party() {
        this.additionalAttributes = new HashMap<>();
    }

    public String getAttribute(String key) {
        return additionalAttributes != null ? additionalAttributes.get(key) : null;
    }
}
