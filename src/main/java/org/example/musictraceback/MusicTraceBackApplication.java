package org.example.musictraceback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class MusicTraceBackApplication {

    public static void main(String[] args) throws IOException {
        // H2 file 模式要求数据目录存在,启动时确保 ./data 已创建
        Files.createDirectories(Path.of("data"));
        SpringApplication.run(MusicTraceBackApplication.class, args);
    }

}
