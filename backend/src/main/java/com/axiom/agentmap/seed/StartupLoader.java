package com.axiom.agentmap.seed;

import com.axiom.agentmap.config.AxiomProperties;
import com.axiom.agentmap.config.DataPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 시작할 때 axiom.startup-mode(full | sample | none, 기본 full)로 DB를 채웁니다. */
@Component
public class StartupLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupLoader.class);
    private final DatasetLoader loader;
    private final AxiomProperties props;
    private final DataPaths paths;

    public StartupLoader(DatasetLoader loader, AxiomProperties props, DataPaths paths) {
        this.loader = loader;
        this.props = props;
        this.paths = paths;
    }

    @Override
    public void run(ApplicationArguments args) {
        if ("none".equalsIgnoreCase(props.getStartupMode())) {
            log.info("axiom.startup-mode=none — 시작 시 데이터를 다시 적재하지 않습니다.");
            return;
        }
        DatasetLoader.ResetResult r = loader.reset(DatasetLoader.parseMode(props.getStartupMode()));
        log.info("데이터 적재 완료 ({}): Agent {}개, 관계 {}개, 미등록 대상 {}개 — 데이터 폴더 {}",
                r.mode(), r.agents(), r.relationships(), r.pendingEntities(), paths.root());
    }
}
