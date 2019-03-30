package org.powerflows;

import lombok.Getter;
import lombok.val;
import org.camunda.bpm.dmn.engine.DmnDecision;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration;
import org.camunda.bpm.engine.variable.context.VariableContext;
import org.camunda.bpm.engine.variable.impl.VariableMapImpl;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.powerflows.dmn.engine.DecisionEngine;
import org.powerflows.dmn.engine.configuration.DefaultDecisionEngineConfiguration;
import org.powerflows.dmn.engine.model.decision.Decision;
import org.powerflows.dmn.engine.model.evaluation.variable.DecisionVariables;
import org.powerflows.dmn.io.xml.XmlDecisionReader;
import org.powerflows.dmn.io.yaml.YamlDecisionReader;
import sun.misc.Unsafe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@State(Scope.Thread)
public class BenchmarkDmnEngines {

    static final byte[] DMN;
    static final byte[] YAML;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe u = (Unsafe) theUnsafe.get(null);

            Class cls = Class.forName("jdk.internal.module.IllegalAccessLogger");
            Field logger = cls.getDeclaredField("logger");
            u.putObjectVolatile(cls, u.staticFieldOffset(logger), null);
        } catch (Exception e) {
        }


        DMN = BenchmarkDmnEngines.loadFile("/loan_term.dmn");
        YAML = BenchmarkDmnEngines.loadFile("/loan_term.yml");
    }

    private static byte[] loadFile(String src) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final InputStream is = BenchmarkDmnEngines.class.getResourceAsStream(src);
        final int bufferSize = 2048;
        byte[] buffer = new byte[bufferSize];

        try {
            int read = is.read(buffer);
            while (read > 0) {
                baos.write(buffer, 0, read);
                if (read < bufferSize) {
                    break;
                }
                read = is.read(buffer);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private DmnEngine camundaEngine = setupCamundaEngine();

    private DecisionEngine decisionEngine = setupPowerflowsEngine();

    private DecisionEngine setupPowerflowsEngine() {
        return new DefaultDecisionEngineConfiguration().configure();
    }

    private DmnEngine setupCamundaEngine() {
        DmnEngineConfiguration configuration = DmnEngineConfiguration
                .createDefaultDmnEngineConfiguration();
        configuration.engineMetricCollector(null);

        return configuration.buildEngine();
    }

//    @Fork(value = 1, warmups = 2)
//    @BenchmarkMode(Mode.Throughput)
//    @Benchmark
//    public void benchCamundaInit() {
//        setupCamundaEngine();
//    }
//
//    @Fork(value = 1, warmups = 2)
//    @BenchmarkMode(Mode.Throughput)
//    @Benchmark
//    public void benchPowerflowsInit() {
//        setupPowerflowsEngine();
//    }
//
//    @Fork(value = 1, warmups = 2)
//    @BenchmarkMode(Mode.Throughput)
//    @Benchmark
//    public void benchCamundaLoadDmnXml() {
//        camundaEngine.parseDecisions(new ByteArrayInputStream(DMN));
//    }
//
//    @Fork(value = 1, warmups = 2)
//    @BenchmarkMode(Mode.Throughput)
//    @Benchmark
//    public void benchPowerflowsLoadDmnXml() {
//        new XmlDecisionReader().readAll(new ByteArrayInputStream(DMN));
//    }
//
//    @Fork(value = 1, warmups = 2)
//    @BenchmarkMode(Mode.Throughput)
//    @Benchmark
//    public void benchPowerflowsLoadDmnYaml() {
//        new YamlDecisionReader().readAll(new ByteArrayInputStream(YAML));
//    }

    @Fork(value = 1, warmups = 2)
    @BenchmarkMode(Mode.Throughput)
    @Benchmark
    public void benchCamundaEvaluateDecisions(CamundaState state) {
        state.getCamundaEngine().evaluateDecision(state.getDecision(), state.nextVars()).getResultList();
    }


    @Fork(value = 1, warmups = 2)
    @BenchmarkMode(Mode.Throughput)
    @Benchmark
//    @Measurement(time = 20, iterations = 8)
    public void benchPowerflowsEvaluateDecisions(PowerflowsState state) {
        state.getDecisionEngine().evaluate(state.getDecision(), state.nextVars()).getCollectionRulesResult();
    }


    static class BaseSate {
        private int age = 1;
        private LocalDate startDate = LocalDate.now().minusYears(10);
        private int activeLoansNumber = 1;

        Map nextVarsMap() {
            val map = new HashMap<>();
            map.put("age", age++);
            map.put("activeLoansNumber", activeLoansNumber++);
            map.put("startDate", Date.from(startDate.plusDays(age).atStartOfDay().toInstant(ZoneOffset.UTC)));

            return map;
        }
    }

    @State(Scope.Thread)
    @Getter
    public static class CamundaState extends BaseSate {
        private final DmnEngine camundaEngine;
        private final DmnDecision decision;

        public CamundaState() {
            DmnEngineConfiguration configuration = DmnEngineConfiguration
                    .createDefaultDmnEngineConfiguration();
            configuration.engineMetricCollector(null);

            camundaEngine = configuration.buildEngine();

            decision = camundaEngine.parseDecision("loan_qualifier", new ByteArrayInputStream(DMN));
        }

        @SuppressWarnings("unchecked")
        public VariableContext nextVars() {
            return new VariableMapImpl(nextVarsMap());
        }
    }

    @State(Scope.Thread)
    @Getter
    public static class PowerflowsState extends BaseSate {
        private final DecisionEngine decisionEngine = new DefaultDecisionEngineConfiguration().configure();
        private final Decision decision;

        public PowerflowsState() {
            decision = new XmlDecisionReader().read(new ByteArrayInputStream(DMN))
                    .orElseThrow(() -> new RuntimeException("Oh nooooo"));
        }

        @SuppressWarnings("unchecked")
        public DecisionVariables nextVars() {
            return new DecisionVariables(nextVarsMap());
        }
    }
}