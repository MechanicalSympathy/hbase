/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.ipc;

import org.apache.hadoop.hbase.shaded.io.netty.buffer.ByteBuf;
import org.apache.hadoop.hbase.shaded.io.netty.channel.Channel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.ipc.RpcServer.CallCleanup;
import org.apache.hadoop.hbase.nio.ByteBuff;
import org.apache.hadoop.hbase.nio.SingleByteBuff;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.BlockingService;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.Descriptors.MethodDescriptor;
import org.apache.hadoop.hbase.shaded.com.google.protobuf.Message;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RPCProtos.RequestHeader;
import org.apache.htrace.TraceInfo;

/**
 * RpcConnection implementation for netty rpc server.
 */
@InterfaceAudience.Private
class NettyServerRpcConnection extends ServerRpcConnection {

  final Channel channel;

  NettyServerRpcConnection(NettyRpcServer rpcServer, Channel channel) {
    super(rpcServer);
    this.channel = channel;
    InetSocketAddress inetSocketAddress = ((InetSocketAddress) channel.remoteAddress());
    this.addr = inetSocketAddress.getAddress();
    if (addr == null) {
      this.hostAddress = "*Unknown*";
    } else {
      this.hostAddress = inetSocketAddress.getAddress().getHostAddress();
    }
    this.remotePort = inetSocketAddress.getPort();
  }

  void process(final ByteBuf buf) throws IOException, InterruptedException {
    if (connectionHeaderRead) {
      this.callCleanup = new RpcServer.CallCleanup() {
        @Override
        public void run() {
          buf.release();
        }
      };
      process(new SingleByteBuff(buf.nioBuffer()));
    } else {
      ByteBuffer connectionHeader = ByteBuffer.allocate(buf.readableBytes());
      buf.readBytes(connectionHeader);
      buf.release();
      process(connectionHeader);
    }
  }

  void process(ByteBuffer buf) throws IOException, InterruptedException {
    process(new SingleByteBuff(buf));
  }

  void process(ByteBuff buf) throws IOException, InterruptedException {
    try {
      if (skipInitialSaslHandshake) {
        skipInitialSaslHandshake = false;
        if (callCleanup != null) {
          callCleanup.run();
        }
        return;
      }

      if (useSasl) {
        saslReadAndProcess(buf);
      } else {
        processOneRpc(buf);
      }
    } catch (Exception e) {
      if (callCleanup != null) {
        callCleanup.run();
      }
      throw e;
    } finally {
      this.callCleanup = null;
    }
  }

  @Override
  public synchronized void close() {
    disposeSasl();
    channel.close();
    callCleanup = null;
  }

  @Override
  public boolean isConnectionOpen() {
    return channel.isOpen();
  }

  @Override
  public NettyServerCall createCall(int id, final BlockingService service,
      final MethodDescriptor md, RequestHeader header, Message param, CellScanner cellScanner,
      long size, TraceInfo tinfo, final InetAddress remoteAddress, int timeout,
      CallCleanup reqCleanup) {
    return new NettyServerCall(id, service, md, header, param, cellScanner, this, size, tinfo,
        remoteAddress, System.currentTimeMillis(), timeout, this.rpcServer.reservoir,
        this.rpcServer.cellBlockBuilder, reqCleanup);
  }

  @Override
  protected void doRespond(RpcResponse resp) {
    channel.writeAndFlush(resp);
  }
}