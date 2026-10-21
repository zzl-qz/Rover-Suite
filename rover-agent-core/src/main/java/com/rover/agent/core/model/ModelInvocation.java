package com.rover.agent.core.model;

import java.util.List;

/** 一次物理模型请求；传输成功与输出契约通过分开记录，tokens 为任务保守核算值。 */
public record ModelInvocation(String id, String scene, ModelIdentity identity, String promptVersion,
                              String policyVersion, String promptDigest, String toolCatalogDigest,
                              List<String> queryTemplateVersions, long startedAtMillis, Long completedAtMillis,
                              String status, String validation, long accountedInputTokens, long accountedOutputTokens,
                              int outputLimit, String returnedModel, String systemFingerprint) {
    public ModelInvocation { queryTemplateVersions = queryTemplateVersions == null ? List.of() : List.copyOf(queryTemplateVersions); }
    public ModelInvocation finish(String outcome, long input, long output, String model, String fingerprint) {
        return new ModelInvocation(id, scene, identity, promptVersion, policyVersion, promptDigest, toolCatalogDigest,
                queryTemplateVersions, startedAtMillis, System.currentTimeMillis(), outcome, validation, input, output,
                outputLimit, identifier(model), identifier(fingerprint));
    }
    public ModelInvocation validated(String result) {
        return new ModelInvocation(id, scene, identity, promptVersion, policyVersion, promptDigest, toolCatalogDigest,
                queryTemplateVersions, startedAtMillis, completedAtMillis, status, result, accountedInputTokens,
                accountedOutputTokens, outputLimit, returnedModel, systemFingerprint);
    }
    private static String identifier(String value) { return value != null && value.matches("[a-zA-Z0-9_.:/@-]{1,128}") ? value : null; }
}
