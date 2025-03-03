package com.fluxtion.dataflow.serverplugin.lib.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JsonlTest {

    @Test
    public void testJsonl() throws JsonProcessingException {
        String in = """
                {"type" : "com.fluxtion.dataflow.serverplugin.lib.json.Person", "name" : "John Doe"}""";
        TypeSerialiser serialiser = new TypeSerialiser();
        var person = serialiser.toObject(in);

        Assertions.assertEquals(new Person("John Doe"), person);
    }

}
