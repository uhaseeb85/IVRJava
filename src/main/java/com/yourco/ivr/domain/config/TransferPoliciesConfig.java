package com.yourco.ivr.domain.config;

import lombok.Data;

import java.util.List;

@Data
public class TransferPoliciesConfig {
    private List<TransferPolicy> policies;
}
