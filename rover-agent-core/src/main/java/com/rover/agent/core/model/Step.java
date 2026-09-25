package com.rover.agent.core.model;

/** 一次调查中的一个步骤记录。 */
public record Step(String name, StepStatus status, String detail) { }