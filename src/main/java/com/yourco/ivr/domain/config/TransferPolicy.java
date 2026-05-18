package com.yourco.ivr.domain.config;

import com.yourco.ivr.domain.AuthLevel;
import com.yourco.ivr.domain.TokenType;
import lombok.Data;

import java.util.List;

@Data
public class TransferPolicy {
    private String sourceSystemId;
    private List<TokenType> honoredTokens;
    private AuthLevel maxHonoredLevel;
    private boolean enabled;
}
