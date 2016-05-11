/*
 * Copyright (c) 2015-2016, Index Data
 * All rights reserved.
 * See the file LICENSE for details.
 */
package okapi.util;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import static okapi.util.ErrorType.INTERNAL;
import static okapi.util.ErrorType.NOT_FOUND;
import static okapi.util.ErrorType.USER;

/**
 * A shared map with extra features like locking and listing of keys.
 * Two level keys.
 * @author heikki
 */

public class LockedStringMap {
  private final Logger logger = LoggerFactory.getLogger("okapi");

  static class StringMap{
    @JsonProperty
    Map<String,String> strings = new LinkedHashMap<>();
  }

  static class KeyList {
    @JsonProperty
    Set<String> keys = new TreeSet<>();
  }

  AsyncMap<String, String> list = null;
  Vertx vertx = null;
  private final int delay = 10; // ms in recursing for retry of map
  private final String allkeys = "_keys"; // keeps a list of all known keys

  public void init(Vertx vertx, String mapName, Handler<ExtendedAsyncResult<Void>> fut) {
    this.vertx = vertx;
    AsyncMapFactory.<String, String>create(vertx, mapName, res -> {
      if (res.succeeded()) {
        this.list = res.result();
        fut.handle(new Success<>());
      } else {
        fut.handle(new Failure<>(INTERNAL, res.cause()));
      }
    });
  }

  public void get(String k, String k2, Handler<ExtendedAsyncResult<String>> fut) {
    StringMap smap = new StringMap();
    list.get(k, resGet -> {
      if (resGet.failed()) {
        fut.handle(new Failure<>(INTERNAL, resGet.cause()));
      } else {
        String val = resGet.result();
        //logger.debug("Get " + k + "/" + k2 + ":" + val);
        if (val != null) {
          StringMap oldlist = Json.decodeValue(val, StringMap.class);
          smap.strings.putAll(oldlist.strings);
          if (smap.strings.containsKey(k2) ) {
            fut.handle(new Success<>(smap.strings.get(k2)));
            return;
          }
        }
        fut.handle(new Failure<>(NOT_FOUND, k + "/" + k2));
      }
    });
  }

  public void get(String k, Handler<ExtendedAsyncResult<Collection<String>>> fut) {
    list.get(k, resGet -> {
      if (resGet.failed()) {
        fut.handle(new Failure<>(INTERNAL, resGet.cause()));
      } else {
        String val = resGet.result();
        //logger.debug("Get " + k + ":" + val);
        if (val != null) {
          StringMap oldlist = Json.decodeValue(val, StringMap.class);
          fut.handle(new Success<>(oldlist.strings.values()));
        } else {
          fut.handle(new Failure<>(NOT_FOUND, k));
        }
      }
    });
  }


  private void addKey(String k,Handler<ExtendedAsyncResult<Void>> fut ) {
    KeyList klist = new KeyList();
    list.get(allkeys, resGet -> {
      if (resGet.failed()) {
        fut.handle(new Failure<>(INTERNAL, resGet.cause()));
      } else {
        String oldVal = resGet.result();
        if (oldVal != null) {
          KeyList oldlist = Json.decodeValue(oldVal, KeyList.class);
          klist.keys.addAll(oldlist.keys);
        }
        if ( ! klist.keys.contains(k)) {
          klist.keys.add(k);
          String newVal = Json.encodePrettily(klist);
          logger.warn("addKey '" + k + "' : " + newVal);
          if (oldVal == null) { // new entry
            list.putIfAbsent(allkeys, newVal, resPut -> {
              if (resPut.succeeded()) {
                if (resPut.result() == null) {
                  fut.handle(new Success<>());
                } else { // Someone messed with it, try again
                  vertx.setTimer(delay, res->{
                    addKey(k, fut);
                  });
                }
              } else {
                fut.handle(new Failure<>(INTERNAL, resPut.cause()));
              }
            });
          } else { // existing entry, put and retry if someone else messed with it
            list.replaceIfPresent(allkeys, oldVal, newVal, resRepl -> {
              if (resRepl.succeeded()) {
                if (resRepl.result()) {
                  fut.handle(new Success<>());
                } else {
                  vertx.setTimer(delay, res->{
                    addKey(k, fut);
                  });
                }
              } else {
                fut.handle(new Failure<>(INTERNAL, resRepl.cause()));
              }
            });
          }
        }
      }
    });
  }

  public void add(String k, String k2, String jsonString, Handler<ExtendedAsyncResult<Void>> fut) {
    StringMap smap = new StringMap();
    list.get(k, resGet -> {
      if (resGet.failed()) {
        fut.handle(new Failure<>(INTERNAL, resGet.cause()));
      } else {
        String oldVal = resGet.result();
        if (oldVal != null) {
          StringMap oldlist = Json.decodeValue(oldVal, StringMap.class);
          smap.strings.putAll(oldlist.strings);
        }
        if (smap.strings.containsKey(k2) ) {
          fut.handle(new Failure<>(USER, "Duplicate instance " + k2));
          return; // TODO - is this an error at all? Probably yes, should not happen with Discovery
        }
        smap.strings.put(k2,jsonString);
        String newVal = Json.encodePrettily(smap);
        //logger.debug("Add: to " + k + ":" + newVal);
        if (oldVal == null) { // new entry
          list.putIfAbsent(k, newVal, resPut -> {
            if (resPut.succeeded()) {
              if (resPut.result() == null) {
                addKey(k,fut);
              } else { // Someone messed with it, try again
                vertx.setTimer(delay, res->{
                  add(k, k2, jsonString, fut);
                });
              }
            } else {
              fut.handle(new Failure<>(INTERNAL, resPut.cause()));
            }
          });
        } else { // existing entry, put and retry if someone else messed with it
          list.replaceIfPresent(k, oldVal, newVal, resRepl -> {
            if (resRepl.succeeded()) {
              if (resRepl.result()) {
                addKey(k,fut);
              } else {
                vertx.setTimer(delay, res->{
                  add(k, k2, jsonString, fut);
                });
              }
            } else {
              fut.handle(new Failure<>(INTERNAL, resRepl.cause()));
            }
          });
        }
      } // get success
    });
  }

public void remove(String k, String k2, Handler<ExtendedAsyncResult<Boolean>> fut) {
    list.get(k, resGet -> {
      if (resGet.failed()) {
        fut.handle(new Failure<>(INTERNAL, resGet.cause()));
      } else {
        String val = resGet.result();
        if (val == null) {
          fut.handle(new Failure<>(NOT_FOUND, k));
          return;
        }
        StringMap smap = Json.decodeValue(val, StringMap.class);
        if (!smap.strings.containsKey(k2)) {
          fut.handle(new Failure<>(NOT_FOUND, k + "/" + k2));
        } else {
          smap.strings.remove(k2);
          if (smap.strings.isEmpty()) {
            list.removeIfPresent(k, val, resDel -> {
              if (resDel.succeeded()) {
                if (resDel.result()) {
                  fut.handle(new Success<>(true));
                  // Note that we do not remove the key from the list
                  // That could lead to race conditions, better to have
                  // unused entried in the key list.
                } else {
                  vertx.setTimer(delay, res -> {
                    remove(k, k2, fut);
                  });
                }
              } else {
                fut.handle(new Failure<>(INTERNAL, resDel.cause()));
              }
            });
          } else { // list was not empty, remove value
            String newVal = Json.encodePrettily(smap);
            list.replaceIfPresent(k, val, newVal, resPut -> {
              if (resPut.succeeded()) {
                if (resPut.result()) {
                  fut.handle(new Success<>(false));
                } else {
                  vertx.setTimer(delay, res -> {
                    remove(k, k2, fut);
                  });
                }
              } else {
                fut.handle(new Failure<>(INTERNAL, resPut.cause()));
              }
            });
          }
        }
      }
    });
  }

}
