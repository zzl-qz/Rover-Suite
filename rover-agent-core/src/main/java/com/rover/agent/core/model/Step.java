package com.rover.agent.core.model;

/**
 * 一次调查中的一个步骤记录。
 *
 * {@code inputSummary} 只在「要做什么」上报后有值，{@code outputSummary} 在产出结果后有值；失败看 {@code error}。
 */
public record Step(String stepId, String taskId, AgentStepType type, String name, StepStatus status,
                   String inputSummary, String outputSummary, long startedAtMillis, long finishedAtMillis,
                   String error) { }