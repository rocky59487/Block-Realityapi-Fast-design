#!/bin/bash
# Check if any tests fail
cd "Block Reality"
./gradlew :api:test --tests com.blockreality.api.physics.ForceEquilibriumSolverTest
