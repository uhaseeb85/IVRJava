package com.yourco.ivr.domain;

import lombok.Data;

import java.util.EnumSet;
import java.util.Set;

@Data
public class CustomerPreference {
    private Set<TokenType> blockedTokens;
    private AuthLevel maxAllowedLevel;
}
