package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.domain.MappingDefinition;
import com.acme.opsweave.integration.domain.MappingDocumentParser;
import com.acme.opsweave.integration.domain.MappingRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Loads every mapping document named by the catalog index. Missing documents fail closed. */
public final class ClasspathMappingCatalog {
    private ClasspathMappingCatalog() {}

    public static MappingRegistry load(ClassLoader loader) {
        String index = read(loader, "mappings/index.txt");
        List<MappingDefinition> definitions = new ArrayList<>();
        for (String line : index.split("\n")) {
            String name = line.trim();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
                throw new IllegalStateException("Mapping catalog entry is invalid");
            }
            definitions.add(MappingDocumentParser.parse(read(loader, "mappings/" + name)));
        }
        return new MappingRegistry(definitions);
    }

    private static String read(ClassLoader loader, String name) {
        try (InputStream input = loader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Mapping catalog is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new IllegalStateException("Mapping catalog is missing");
        }
    }
}
