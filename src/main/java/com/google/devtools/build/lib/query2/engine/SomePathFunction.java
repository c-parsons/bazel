// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.query2.engine;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A somepath(x, y) query expression, which computes the set of nodes
 * on some arbitrary path from a target in set x to a target in set y.
 *
 * <pre>expr ::= SOMEPATH '(' expr ',' expr ')'</pre>
 */
class SomePathFunction implements QueryFunction {
  SomePathFunction() {
  }

  @Override
  public String getName() {
    return "somepath";
  }

  @Override
  public int getMandatoryArguments() {
    return 2;
  }

  @Override
  public List<ArgumentType> getArgumentTypes() {
    return ImmutableList.of(ArgumentType.EXPRESSION, ArgumentType.EXPRESSION);
  }

  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env, QueryExpression expression, List<Argument> args)
      throws QueryException, InterruptedException {
    Set<T> fromValue = args.get(0).getExpression().eval(env);
    Set<T> toValue = args.get(1).getExpression().eval(env);

    // Implementation strategy: for each x in "from", compute its forward
    // transitive closure.  If it intersects "to", then do a path search from x
    // to an arbitrary node in the intersection, and return the path.  This
    // avoids computing the full transitive closure of "from" in some cases.

    env.buildTransitiveClosure(expression, fromValue, Integer.MAX_VALUE);

    // This set contains all nodes whose TC does not intersect "toValue".
    Set<T> done = new HashSet<>();

    for (T x : fromValue) {
      if (done.contains(x)) {
        continue;
      }
      Set<T> xtc = env.getTransitiveClosure(ImmutableSet.of(x));
      SetView<T> result;
      if (xtc.size() > toValue.size()) {
        result = Sets.intersection(toValue, xtc);
      } else {
        result = Sets.intersection(xtc, toValue);
      }
      if (!result.isEmpty()) {
        return env.getNodesOnPath(x, result.iterator().next());
      }
      done.addAll(xtc);
    }
    return ImmutableSet.of();
  }
}
