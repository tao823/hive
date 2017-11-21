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

package org.apache.hadoop.hive.common.jsonexplain.tez;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.hive.common.jsonexplain.JsonUtils;
import org.json.simple.JSONObject;

public class Op {
  String name;
  String operatorId;
  Op parent;
  List<Op> children;
  List<Attr> attrs;
  // the jsonObject for this operator
  JSONObject opObject;
  // the vertex that this operator belongs to
  Vertex vertex;
  // the vertex that this operator output to if this operator is a
  // ReduceOutputOperator
  String outputVertexName;

  public Op(String name, String id, String outputVertexName, List<Op> children, List<Attr> attrs,
      JSONObject opObject, Vertex vertex) {
    super();
    this.name = name;
    this.operatorId = id;
    this.outputVertexName = outputVertexName;
    this.children = children;
    this.attrs = attrs;
    this.opObject = opObject;
    this.vertex = vertex;
  }

  @VisibleForTesting
  void inlineJoinOp() throws Exception {
    // inline map join operator
    if (this.name.equals("Map Join Operator")) {
      JSONObject mapjoinObj = (JSONObject) opObject.get("Map Join Operator");
      // get the map for posToVertex
      JSONObject verticeObj = (JSONObject) mapjoinObj.get("input vertices:");
      Map<String, String> posToVertex = new HashMap<>();
      for (String pos : JsonUtils.getNames(verticeObj)) {
        String vertexName = verticeObj.get(pos).toString();
        posToVertex.put(pos, vertexName);
        // update the connection
        Connection c = null;
        for (Connection connection : vertex.parentConnections) {
          if (connection.from.name.equals(vertexName)) {
            c = connection;
            break;
          }
        }
        if (c != null) {
          TezJsonParser.addInline(this, c);
        }
      }
      // update the attrs
      removeAttr("input vertices:");
      // update the keys to use vertex name
      JSONObject keys = (JSONObject) mapjoinObj.get("keys:");
      if (keys.size() != 0) {
        JSONObject newKeys = new JSONObject();
        for (String key : JsonUtils.getNames(keys)) {
          String vertexName = posToVertex.get(key);
          if (vertexName != null) {
            newKeys.put(vertexName, keys.get(key));
          } else {
            newKeys.put(this.vertex.name, keys.get(key));
          }
        }
        // update the attrs
        removeAttr("keys:");
        this.attrs.add(new Attr("keys:", newKeys.toString()));
      }
    }
    // inline merge join operator in a self-join
    else if (this.name.equals("Merge Join Operator")) {
      if (this.vertex != null) {
        for (Vertex v : this.vertex.mergeJoinDummyVertexs) {
          TezJsonParser.addInline(this, new Connection(null, v));
        }
      }
    } else {
      throw new Exception("Unknown join operator");
    }
  }

  private String getNameWithOpId() {
    if (operatorId != null) {
      return this.name + " [" + operatorId + "]";
    } else {
      return this.name;
    }
  }

  /**
   * @param out
   * @param indentFlag
   * @param branchOfJoinOp
   *          This parameter is used to show if it is a branch of a Join
   *          operator so that we can decide the corresponding indent.
   * @throws Exception
   */
  public void print(PrintStream out, List<Boolean> indentFlag, boolean branchOfJoinOp)
      throws Exception {
    // print name
    if (TezJsonParser.printSet.contains(this)) {
      out.println(TezJsonParser.prefixString(indentFlag) + " Please refer to the previous "
          + this.getNameWithOpId());
      return;
    }
    TezJsonParser.printSet.add(this);
    if (!branchOfJoinOp) {
      out.println(TezJsonParser.prefixString(indentFlag) + this.getNameWithOpId());
    } else {
      out.println(TezJsonParser.prefixString(indentFlag, "|<-") + this.getNameWithOpId());
    }
    branchOfJoinOp = false;
    // if this operator is a join operator
    if (this.name.contains("Join")) {
      inlineJoinOp();
      branchOfJoinOp = true;
    }
    // if this operator is the last operator, we summarize the non-inlined
    // vertex
    List<Connection> noninlined = new ArrayList<>();
    if (this.parent == null) {
      if (this.vertex != null) {
        for (Connection connection : this.vertex.parentConnections) {
          if (!TezJsonParser.isInline(connection.from)) {
            noninlined.add(connection);
          }
        }
      }
    }
    // print attr
    List<Boolean> attFlag = new ArrayList<>();
    attFlag.addAll(indentFlag);
    // should print | if (1) it is branchOfJoinOp or (2) it is the last op and
    // has following non-inlined vertex
    if (branchOfJoinOp || (this.parent == null && !noninlined.isEmpty())) {
      attFlag.add(true);
    } else {
      attFlag.add(false);
    }
    Collections.sort(attrs);
    for (Attr attr : attrs) {
      out.println(TezJsonParser.prefixString(attFlag) + attr.toString());
    }
    // print inline vertex
    if (TezJsonParser.inlineMap.containsKey(this)) {
      for (int index = 0; index < TezJsonParser.inlineMap.get(this).size(); index++) {
        Connection connection = TezJsonParser.inlineMap.get(this).get(index);
        List<Boolean> vertexFlag = new ArrayList<>();
        vertexFlag.addAll(indentFlag);
        if (branchOfJoinOp) {
          vertexFlag.add(true);
        }
        // if there is an inline vertex but the operator itself is not on a join
        // branch,
        // then it means it is from a vertex created by an operator tree,
        // e.g., fetch operator, etc.
        else {
          vertexFlag.add(false);
        }
        connection.from.print(out, vertexFlag, connection.type, this.vertex);
      }
    }
    // print parent op, i.e., where data comes from
    if (this.parent != null) {
      List<Boolean> parentFlag = new ArrayList<>();
      parentFlag.addAll(indentFlag);
      parentFlag.add(false);
      this.parent.print(out, parentFlag, branchOfJoinOp);
    }
    // print next vertex
    else {
      for (int index = 0; index < noninlined.size(); index++) {
        Vertex v = noninlined.get(index).from;
        List<Boolean> vertexFlag = new ArrayList<>();
        vertexFlag.addAll(indentFlag);
        if (index != noninlined.size() - 1) {
          vertexFlag.add(true);
        } else {
          vertexFlag.add(false);
        }
        v.print(out, vertexFlag, noninlined.get(index).type, this.vertex);
      }
    }
  }

  public void removeAttr(String name) {
    int removeIndex = -1;
    for (int index = 0; index < attrs.size(); index++) {
      if (attrs.get(index).name.equals(name)) {
        removeIndex = index;
        break;
      }
    }
    if (removeIndex != -1) {
      attrs.remove(removeIndex);
    }
  }
}