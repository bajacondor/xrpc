/*
 * Copyright 2017 Nordstrom, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nordstrom.xrpc.demo;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.nordstrom.xrpc.XConfig;
import com.nordstrom.xrpc.demo.proto.Dino;
import com.nordstrom.xrpc.http.Handler;
import com.nordstrom.xrpc.http.Recipes;
import com.nordstrom.xrpc.http.Router;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Example {
  /** Example POJO for use in request / response. */
  @AllArgsConstructor
  private static class Person {
    private String name;
  }

  public static void main(String[] args) {
    final List<Person> people = new ArrayList<>();
    final List<Dino> dinos = new ArrayList<>();

    // See https://github.com/square/moshi for the Moshi Magic.
    Moshi moshi = new Moshi.Builder().build();
    Type type = Types.newParameterizedType(List.class, Person.class);
    JsonAdapter<List<Person>> adapter = moshi.adapter(type);

    // Load application config from jar resources. The 'load' method below also allows supports
    // overrides from environment variables.
    Config config = ConfigFactory.load("demo.conf");

    // Build your router. This overrides the default configuration with values from
    // src/main/resources/demo.conf.
    XConfig xConfig = new XConfig(config.getConfig("xrpc"));
    Router router = new Router(xConfig);

    // Define a simple function call.
    Handler peopleHandler =
        context -> {
          return Recipes.newResponseOk(
              adapter.toJson(people), Recipes.ContentType.Application_Json);
        };

    // Define a complex function call
    Handler personHandler =
        context -> {
          Person p = new Person(context.variable("person"));
          people.add(p);

          return Recipes.newResponseOk("");
        };

    // do some proto
    Handler dinosHandler =
        context -> {
          // TODO(jkinkead): Clean this up; we should have a helper to handle this.
          Dino output = dinos.get(0);
          ByteBuf bb = Unpooled.compositeBuffer();
          bb.ensureWritable(CodedOutputStream.computeMessageSizeNoTag(output), true);
          try {
            output.writeTo(new ByteBufOutputStream(bb));
            HttpResponse response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, bb);
            response.headers().set(CONTENT_TYPE, "application/octet-stream");
            response.headers().setInt(CONTENT_LENGTH, bb.readableBytes());
            return response;
          } catch (IOException e) {
            log.error("Dino Error", (Throwable) e);
            HttpResponse response =
                new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            response.headers().set(CONTENT_TYPE, "text/plain");
            response.headers().setInt(CONTENT_LENGTH, 0);
            return response;
          }
        };

    // Define a complex function call with Proto
    Handler dinoHandler =
        context -> {
          try {
            // TODO(jkinkead): Clean this up; we should have a helper to handle this.
            Optional<Dino> d;
            d =
                Optional.of(
                    Dino.parseFrom(
                        CodedInputStream.newInstance(
                            ((FullHttpRequest) context).content().nioBuffer())));
            d.ifPresent(dinos::add);
          } catch (IOException e) {
            log.error("Dino Error", (Throwable) e);
            HttpResponse response =
                new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
            response.headers().set(CONTENT_TYPE, "text/plain");
            response.headers().setInt(CONTENT_LENGTH, 0);

            return response;
          }

          HttpResponse response =
              new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
          response.headers().set(CONTENT_TYPE, "text/plain");
          response.headers().setInt(CONTENT_LENGTH, 0);

          return response;
        };

    // Define a simple function call
    Handler healthCheckHandler =
        context -> {
          return Recipes.newResponseOk("");
        };

    // Create your route mapping
    router.addRoute("/people/{person}", personHandler);
    router.addRoute("/people", peopleHandler);

    // Create your route mapping
    router.addRoute("/dinos/{dino}", dinoHandler);
    router.addRoute("/dinos", dinosHandler);

    // Health Check for k8s
    router.addRoute("/health", healthCheckHandler);

    try {
      // Fire away
      router.listenAndServe();
    } catch (IOException e) {
      log.error("Failed to start people server", e);
    }
  }
}
