/*
 * Copyright Terracotta, Inc.
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

package org.ehcache.clustered.client.internal.store.operations;

import org.ehcache.clustered.client.internal.store.operations.codecs.OperationCodecFactory;

import static org.ehcache.clustered.client.internal.store.operations.codecs.OperationCodecFactory.PutOperationCodecFactory;
import static org.ehcache.clustered.client.internal.store.operations.codecs.OperationCodecFactory.RemoveOperationCodecFactory;
import static org.ehcache.clustered.client.internal.store.operations.codecs.OperationCodecFactory.PutIfAbsentOperationCodecFactory;

public enum OperationCode {

  PUT((byte)1, new PutOperationCodecFactory()),
  REMOVE((byte)2, new RemoveOperationCodecFactory()),
  PUT_IF_ABSENT((byte)3, new PutIfAbsentOperationCodecFactory());

  private byte value;
  private OperationCodecFactory codecFactory;

  OperationCode(byte value, OperationCodecFactory codecFactory) {
    this.value = value;
    this.codecFactory = codecFactory;
  }

  public byte getValue() {
    return value;
  }

  public OperationCodecFactory getCodecFactory() {
    return codecFactory;
  }

  public static OperationCode valueOf(byte value) {
    switch (value) {
      case 1:
        return PUT;
      case 2:
        return REMOVE;
      case 3:
        return PUT_IF_ABSENT;
      default:
        throw new IllegalArgumentException("Operation undefined for the value " + value);
    }
  }
}
