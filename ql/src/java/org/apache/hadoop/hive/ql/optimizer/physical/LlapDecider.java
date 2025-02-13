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

package org.apache.hadoop.hive.ql.optimizer.physical;

import static org.apache.hadoop.hive.ql.optimizer.physical.LlapDecider.LlapMode.all;
import static org.apache.hadoop.hive.ql.optimizer.physical.LlapDecider.LlapMode.auto;
import static org.apache.hadoop.hive.ql.optimizer.physical.LlapDecider.LlapMode.map;
import static org.apache.hadoop.hive.ql.optimizer.physical.LlapDecider.LlapMode.none;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.ql.exec.FilterOperator;
import org.apache.hadoop.hive.ql.exec.FunctionInfo;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.exec.GroupByOperator;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.ScriptOperator;
import org.apache.hadoop.hive.ql.exec.SelectOperator;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.tez.TezTask;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedInputFormatInterface;
import org.apache.hadoop.hive.ql.lib.DefaultGraphWalker;
import org.apache.hadoop.hive.ql.lib.DefaultRuleDispatcher;
import org.apache.hadoop.hive.ql.lib.Dispatcher;
import org.apache.hadoop.hive.ql.lib.GraphWalker;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.lib.NodeProcessor;
import org.apache.hadoop.hive.ql.lib.NodeProcessorCtx;
import org.apache.hadoop.hive.ql.lib.Rule;
import org.apache.hadoop.hive.ql.lib.RuleRegExp;
import org.apache.hadoop.hive.ql.lib.TaskGraphWalker;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.AggregationDesc;
import org.apache.hadoop.hive.ql.plan.BaseWork;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeGenericFuncDesc;
import org.apache.hadoop.hive.ql.plan.GroupByDesc;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.ql.plan.PartitionDesc;
import org.apache.hadoop.hive.ql.plan.ReduceWork;
import org.apache.hadoop.hive.ql.plan.SelectDesc;
import org.apache.hadoop.hive.ql.plan.Statistics;
import org.apache.hadoop.hive.ql.plan.TezWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LlapDecider takes care of tagging certain vertices in the execution graph as
 * "llap", which in turn causes them to be submitted to an llap daemon instead
 * of a regular yarn container.
 *
 * The actual algorithm used is driven by LLAP_EXECUTION_MODE. "all", "none" and
 * "map" mechanically tag those elements. "auto" tries to be smarter by looking
 * for suitable vertices.
 *
 * Regardless of the algorithm used, it's always ensured that there's not user
 * code that will be sent to the daemon (ie.: script operators, temporary
 * functions, etc)
 */
public class LlapDecider implements PhysicalPlanResolver {

  protected static transient final Logger LOG = LoggerFactory.getLogger(LlapDecider.class);

  private HiveConf conf;

  public enum LlapMode {
    map, // map operators only
    all, // all operators
    none, // no operators
    auto // please hive, choose for me
  }

  private LlapMode mode;

  class LlapDecisionDispatcher implements Dispatcher {
    private final HiveConf conf;
    private final boolean doSkipUdfCheck;
    private final boolean arePermanentFnsAllowed;
    private final boolean shouldUber;

    public LlapDecisionDispatcher(PhysicalContext pctx, LlapMode mode) {
      conf = pctx.getConf();
      doSkipUdfCheck = HiveConf.getBoolVar(conf, ConfVars.LLAP_SKIP_COMPILE_UDF_CHECK);
      arePermanentFnsAllowed = HiveConf.getBoolVar(conf, ConfVars.LLAP_ALLOW_PERMANENT_FNS);
      // Don't user uber in "all" mode - everything can go into LLAP, which is better than uber.
      shouldUber = HiveConf.getBoolVar(conf, ConfVars.LLAP_AUTO_ALLOW_UBER) && (mode != all);
    }

    @Override
    public Object dispatch(Node nd, Stack<Node> stack, Object... nodeOutputs)
      throws SemanticException {
      @SuppressWarnings("unchecked")
      Task<? extends Serializable> currTask = (Task<? extends Serializable>) nd;
      if (currTask instanceof TezTask) {
        TezWork work = ((TezTask) currTask).getWork();
        for (BaseWork w: work.getAllWork()) {
          handleWork(work, w);
        }
      }
      return null;
    }

    private void handleWork(TezWork tezWork, BaseWork work)
      throws SemanticException {
      if (evaluateWork(tezWork, work)) {
        convertWork(tezWork, work);
      } else {
        if (mode == all) {
          throw new SemanticException("Llap mode is set to all but cannot run work in llap mode." +
              "Set " + HiveConf.ConfVars.LLAP_EXECUTION_MODE + " = auto or set " +
              HiveConf.ConfVars.HIVE_EXECUTION_MODE + " = container");
        }
      }
    }

    private void convertWork(TezWork tezWork, BaseWork work)
      throws SemanticException {

      if (shouldUber) {
        // let's see if we can go one step further and just uber this puppy
        if (tezWork.getChildren(work).isEmpty()
            && work instanceof ReduceWork
            && ((ReduceWork) work).getNumReduceTasks() == 1) {
          work.setUberMode(true);
        }
      }

      // always mark as llap
      work.setLlapMode(true);
    }

    private boolean evaluateWork(TezWork tezWork, BaseWork work)
      throws SemanticException {

      LOG.info("Evaluating work item: " + work.getName());

      // no means no
      if (mode == none) {
        return false;
      }

      // first we check if we *can* run in llap. If we need to use
      // user code to do so (script/udf) we don't.
      /*if (work instanceof MapWork && ((MapWork)work).isUseOneNullRowInputFormat()) {
        // LLAP doesn't support file-based splits that this forces.
        return false;
      }*/

      if (!evaluateOperators(work)) {
        LOG.info("some operators cannot be run in llap");
        return false;
      }

      // --- From here on out we choose whether we *want* to run in llap

      // if mode is all just run it
      if (mode == all) {
        return true;
      }

      // if map mode run iff work is map work
      if (mode == map) {
        return work instanceof MapWork;
      }

      // --- From here we evaluate the auto mode
      assert mode == auto;

      // if parents aren't in llap neither should the child
      if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.LLAP_AUTO_ENFORCE_TREE)
          && !checkParentsInLlap(tezWork, work)) {
        LOG.info("Parent not in llap.");
        return false;
      }

      // only vectorized orc input is cached. so there's a reason to
      // limit to that for now.
      if (work instanceof MapWork
          && HiveConf.getBoolVar(conf, HiveConf.ConfVars.LLAP_AUTO_ENFORCE_VECTORIZED)
          && !checkInputsVectorized((MapWork) work)) {
        LOG.info("Inputs not vectorized.");
        return false;
      }

      // check if there's at least some degree of stats available
      if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.LLAP_AUTO_ENFORCE_STATS)
          && !checkPartialStatsAvailable(work)) {
        LOG.info("No column stats available.");
        return false;
      }

      // now let's take a look at input sizes
      long maxInput = HiveConf.getLongVar(conf, HiveConf.ConfVars.LLAP_AUTO_MAX_INPUT);
      long expectedInput = computeInputSize(work);
      if (maxInput >= 0 && (expectedInput > maxInput)) {
        LOG.info(String.format("Inputs too big (%d > %d)", expectedInput, maxInput));
        return false;
      }

      // and finally let's check output sizes
      long maxOutput = HiveConf.getLongVar(conf, HiveConf.ConfVars.LLAP_AUTO_MAX_OUTPUT);
      long expectedOutput = computeOutputSize(work);
      if (maxOutput >= 0 && (expectedOutput > maxOutput)) {
        LOG.info(String.format("Outputs too big (%d > %d)", expectedOutput, maxOutput));
        return false;
      }

      // couldn't convince you otherwise? well then let's llap.
      LOG.info("Can run work " + work.getName() + " in llap mode.");
      return true;
    }

    private boolean checkExpression(ExprNodeDesc expr) {
      Deque<ExprNodeDesc> exprs = new LinkedList<ExprNodeDesc>();
      exprs.add(expr);
      while (!exprs.isEmpty()) {
        if (LOG.isDebugEnabled()) {
          LOG.debug(String.format("Checking '%s'",expr.getExprString()));
        }

        ExprNodeDesc cur = exprs.removeFirst();
        if (cur == null) continue;
        if (cur.getChildren() != null) {
          exprs.addAll(cur.getChildren());
        }

        if (!doSkipUdfCheck && cur instanceof ExprNodeGenericFuncDesc) {
          ExprNodeGenericFuncDesc funcDesc = (ExprNodeGenericFuncDesc)cur;
          boolean isBuiltIn = FunctionRegistry.isBuiltInFuncExpr(funcDesc);
          if (!isBuiltIn) {
            if (!arePermanentFnsAllowed) {
              LOG.info("Not a built-in function: " + cur.getExprString()
                + " (permanent functions are disabled)");
              return false;
            }
            if (!FunctionRegistry.isPermanentFunction(funcDesc)) {
              LOG.info("Not a built-in or permanent function: " + cur.getExprString());
              return false;
            }
          }
        }
      }
      return true;
    }

    private boolean checkAggregator(AggregationDesc agg) throws SemanticException {
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Checking '%s'", agg.getExprString()));
      }

      boolean result = checkExpressions(agg.getParameters());
      FunctionInfo fi = FunctionRegistry.getFunctionInfo(agg.getGenericUDAFName());
      result = result && (fi != null) && fi.isNative();
      if (!result) {
        LOG.info("Aggregator is not native: " + agg.getExprString());
      }
      return result;
    }

    private boolean checkExpressions(Collection<ExprNodeDesc> exprs) {
      for (ExprNodeDesc expr : exprs) {
        if (!checkExpression(expr)) return false;
      }
      return true;
    }

    private boolean checkAggregators(Collection<AggregationDesc> aggs) {
      try {
        for (AggregationDesc agg: aggs) {
          if (!checkAggregator(agg)) return false;
        }
      } catch (SemanticException e) {
        LOG.warn("Exception testing aggregators.",e);
        return false;
      }
      return true;
    }

    private Map<Rule, NodeProcessor> getRules() {
      Map<Rule, NodeProcessor> opRules = new LinkedHashMap<Rule, NodeProcessor>();
      opRules.put(new RuleRegExp("No scripts", ScriptOperator.getOperatorName() + "%"),
          new NodeProcessor() {
          @Override
          public Object process(Node n, Stack<Node> s, NodeProcessorCtx c,
              Object... os) {
            LOG.debug("Cannot run operator [" + n + "] in llap mode.");
            return new Boolean(false);
          }
        });
      opRules.put(new RuleRegExp("No user code in fil", FilterOperator.getOperatorName() + "%"),
          new NodeProcessor() {
          @Override
          public Object process(Node n, Stack<Node> s, NodeProcessorCtx c,
              Object... os) {
            ExprNodeDesc expr = ((FilterOperator)n).getConf().getPredicate();
            Boolean retval = new Boolean(checkExpression(expr));
            if (!retval) {
              LOG.info("Cannot run filter operator [" + n + "] in llap mode");
            }
            return new Boolean(retval);
          }
        });
      opRules.put(new RuleRegExp("No user code in gby", GroupByOperator.getOperatorName() + "%"),
          new NodeProcessor() {
          @Override
          public Object process(Node n, Stack<Node> s, NodeProcessorCtx c,
              Object... os) {
            @SuppressWarnings("unchecked")
            List<AggregationDesc> aggs = ((Operator<GroupByDesc>) n).getConf().getAggregators();
            Boolean retval = new Boolean(checkAggregators(aggs));
            if (!retval) {
              LOG.info("Cannot run group by operator [" + n + "] in llap mode");
            }
            return new Boolean(retval);
          }
        });
      opRules.put(new RuleRegExp("No user code in select", SelectOperator.getOperatorName() + "%"),
          new NodeProcessor() {
          @Override
          public Object process(Node n, Stack<Node> s, NodeProcessorCtx c,
              Object... os) {
            @SuppressWarnings({ "unchecked" })
            List<ExprNodeDesc> exprs = ((Operator<SelectDesc>) n).getConf().getColList();
            Boolean retval = new Boolean(checkExpressions(exprs));
            if (!retval) {
              LOG.info("Cannot run select operator [" + n + "] in llap mode");
            }
            return new Boolean(retval);
          }
        });

      return opRules;
    }

    private boolean evaluateOperators(BaseWork work) throws SemanticException {
      // lets take a look at the operators. we're checking for user
      // code in those. we will not run that in llap.
      Dispatcher disp = new DefaultRuleDispatcher(null, getRules(), null);
      GraphWalker ogw = new DefaultGraphWalker(disp);

      ArrayList<Node> topNodes = new ArrayList<Node>();
      topNodes.addAll(work.getAllRootOperators());

      HashMap<Node, Object> nodeOutput = new HashMap<Node, Object>();
      ogw.startWalking(topNodes, nodeOutput);

      for (Node n : nodeOutput.keySet()) {
        if (nodeOutput.get(n) != null) {
          if (!((Boolean)nodeOutput.get(n))) {
            LOG.info("Cannot run in LLAP mode.");
            return false;
          }
        }
      }
      return true;
    }

    private boolean checkParentsInLlap(TezWork tezWork, BaseWork base) {
      for (BaseWork w: tezWork.getParents(base)) {
        if (!w.getLlapMode()) {
          LOG.info("Not all parents are run in llap");
          return false;
        }
      }
      return true;
    }

    private boolean checkInputsVectorized(MapWork mapWork) {
      for (String path : mapWork.getPathToPartitionInfo().keySet()) {
        PartitionDesc pd = mapWork.getPathToPartitionInfo().get(path);
        List<Class<?>> interfaceList =
          Arrays.asList(pd.getInputFileFormatClass().getInterfaces());
        if (!interfaceList.contains(VectorizedInputFormatInterface.class)) {
          LOG.info("Input format: " + pd.getInputFileFormatClassName()
            + ", doesn't provide vectorized input");
          return false;
        }
      }
      return true;
    }

    private boolean checkPartialStatsAvailable(BaseWork base) {
      for (Operator<?> o: base.getAllRootOperators()) {
        if (o.getStatistics().getColumnStatsState() == Statistics.State.NONE) {
          return false;
        }
      }
      return true;
    }

    private long computeEdgeSize(BaseWork base, boolean input) {
      long size = 0;
      for (Operator<?> o: (input ? base.getAllRootOperators() : base.getAllLeafOperators())) {
        if (o.getStatistics() == null) {
          // return worst case if unknown
          return Long.MAX_VALUE;
        }

        long currSize = o.getStatistics().getDataSize();
        if ((currSize < 0) || ((Long.MAX_VALUE - size) < currSize)) {
          // overflow
          return Long.MAX_VALUE;
        }
        size += currSize;
      }
      return size;
    }

    private long computeInputSize(BaseWork base) {
      return computeEdgeSize(base, true);
    }

    private long computeOutputSize(BaseWork base) {
      return computeEdgeSize(base, false);
    }
  }

  @Override
  public PhysicalContext resolve(PhysicalContext pctx) throws SemanticException {
    this.conf = pctx.getConf();

    this.mode = LlapMode.valueOf(HiveConf.getVar(conf, HiveConf.ConfVars.LLAP_EXECUTION_MODE));
    LOG.info("llap mode: " + this.mode);

    if (mode == none) {
      LOG.info("LLAP disabled.");
      return pctx;
    }

    // create dispatcher and graph walker
    Dispatcher disp = new LlapDecisionDispatcher(pctx, mode);
    TaskGraphWalker ogw = new TaskGraphWalker(disp);

    // get all the tasks nodes from root task
    ArrayList<Node> topNodes = new ArrayList<Node>();
    topNodes.addAll(pctx.getRootTasks());

    // begin to walk through the task tree.
    ogw.startWalking(topNodes, null);
    return pctx;
  }
}
