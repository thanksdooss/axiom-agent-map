package com.axiom.agentmap.config;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

/** axiom.data-dir 아래 폴더 위치. 상대 경로는 실행 위치(working dir) 기준, 절대 경로도 허용. */
@Component
public class DataPaths {
    private final Path root;
    private final ZoneId zone;

    public DataPaths(AxiomProperties props) {
        Path p = Path.of(props.getDataDir());
        this.root = (p.isAbsolute() ? p : Path.of(System.getProperty("user.dir")).resolve(p)).normalize();
        this.zone = ZoneId.of(props.getTimezone());
    }

    public Path root() { return root; }
    public Path vocabulary() { return root.resolve("vocabulary").resolve("vocabulary.json"); }
    public Path answerKey() { return root.resolve("answer_key").resolve("extraction_answer_key.json"); }
    public Path rawDir() { return root.resolve("raw"); }

    public boolean exists() { return Files.isDirectory(root); }

    /** 오늘 날짜 YYYY-MM-DD (axiom.timezone 기준) */
    public String today() { return LocalDate.now(zone).toString(); }
}
