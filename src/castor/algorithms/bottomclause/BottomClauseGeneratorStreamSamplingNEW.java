package castor.algorithms.bottomclause;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import aima.core.logic.fol.parsing.ast.Constant;
import aima.core.logic.fol.parsing.ast.Predicate;
import aima.core.logic.fol.parsing.ast.Term;
import aima.core.logic.fol.parsing.ast.Variable;
import castor.dataaccess.db.BottomClauseConstructionDAO;
import castor.dataaccess.db.GenericDAO;
import castor.dataaccess.db.GenericTableObject;
import castor.hypotheses.MyClause;
import castor.language.IdentifierType;
import castor.language.Mode;
import castor.language.Schema;
import castor.language.Tuple;
import castor.mappings.MyClauseToIDAClause;
import castor.sampling.JoinEdge;
import castor.sampling.JoinNode;
import castor.sampling.SamplingUtils;
import castor.settings.DataModel;
import castor.settings.Parameters;
import castor.utils.Commons;
import castor.utils.Triple;

public class BottomClauseGeneratorStreamSamplingNEW implements BottomClauseGenerator {

	private static final String SELECT_WHERE_SQL_STATEMENT = "SELECT * FROM %s WHERE %s = %s;";
	
	private int varCounter;
	private int seed;

	public BottomClauseGeneratorStreamSamplingNEW(int seed) {
		this.seed = seed;
		this.varCounter = 0;
	}
	
	@Override
	public MyClause generateBottomClause(GenericDAO genericDAO, BottomClauseConstructionDAO bottomClauseConstructionDAO,
			Tuple exampleTuple, Schema schema, DataModel dataModel, Parameters parameters) {
		return generateBottomClause(genericDAO, bottomClauseConstructionDAO, exampleTuple, schema, dataModel,
				parameters, false);
	}

	@Override
	public MyClause generateGroundBottomClause(GenericDAO genericDAO,
			BottomClauseConstructionDAO bottomClauseConstructionDAO, Tuple exampleTuple, Schema schema,
			DataModel dataModel, Parameters parameters) {
		return generateBottomClause(genericDAO, bottomClauseConstructionDAO, exampleTuple, schema, dataModel,
				parameters, true);
	}

	@Override
	public String generateGroundBottomClauseString(GenericDAO genericDAO,
			BottomClauseConstructionDAO bottomClauseConstructionDAO, Tuple exampleTuple, Schema schema,
			DataModel dataModel, Parameters parameters) {
		MyClause clause = generateGroundBottomClause(genericDAO, bottomClauseConstructionDAO, exampleTuple, schema,
				dataModel, parameters);
		return clause.toString2(MyClauseToIDAClause.POSITIVE_SYMBOL, MyClauseToIDAClause.NEGATE_SYMBOL);
	}
	
	public MyClause generateBottomClause(GenericDAO genericDAO, BottomClauseConstructionDAO bottomClauseConstructionDAO,
			Tuple exampleTuple, Schema schema, DataModel dataModel, Parameters parameters, boolean ground) {
		Random randomGenerator = new Random(seed);
		
		// Check that arities of example and modeH match
		if (dataModel.getModeH().getArguments().size() != exampleTuple.getValues().size()) {
			throw new IllegalArgumentException("Example arity does not match modeH arity.");
		}
		
		int sampleSize;
		if (ground)
			sampleSize = parameters.getGroundRecall();
		else
			sampleSize = parameters.getRecall();
		
		varCounter = 0;
		MyClause clause = new MyClause();
		Map<String, String> hashConstantToVariable = new HashMap<String, String>();
		
		// <relation,depth,tuple> -> size
		// need relation in case the same tuple appears in multiple relations
		// need depth in case a relation appears at different depths in the join tree
		Map<Triple<String,Integer,Tuple>,Long> joinPathSizes = new HashMap<Triple<String,Integer,Tuple>,Long>();
		
		// Create head literal
		Mode modeH = dataModel.getModeH();
		if (ground) {
			modeH = modeH.toGroundMode();
		}
		Predicate headLiteral = createLiteralFromTuple(hashConstantToVariable, exampleTuple, modeH, true);
		clause.addPositiveLiteral(headLiteral);
		
		// Group modes by relation name
		Map<String, List<Mode>> groupedModes = new LinkedHashMap<String, List<Mode>>();
		for (Mode mode : dataModel.getModesB()) {
			if (!groupedModes.containsKey(mode.getPredicateName())) {
				groupedModes.put(mode.getPredicateName(), new LinkedList<Mode>());
			}
			groupedModes.get(mode.getPredicateName()).add(mode);
		}
		
		// Compute join tree
		// Maximum depth of join tree is parameters.iterations 
		JoinNode node = SamplingUtils.findJoinTree(dataModel, parameters);
		
		// Sample from all relations
		for (int i=0; i<sampleSize; i++) {
			for (JoinEdge joinEdge : node.getEdges()) {
				generateBottomClauseAux(genericDAO, schema, exampleTuple, joinEdge, groupedModes, hashConstantToVariable, randomGenerator, clause, ground, joinPathSizes, 1);
			}
		}
		
		return clause;
	}
	
	/*
	 * Implements idea of Acyclic-Stream-Sample for bottom-clause construction
	 */
	private void generateBottomClauseAux(GenericDAO genericDAO, Schema schema, 
			Tuple tuple, JoinEdge joinEdge, 
			Map<String, List<Mode>> groupedModes, Map<String, String> hashConstantToVariable, 
			Random randomGenerator, MyClause clause, boolean ground,
			Map<Triple<String,Integer,Tuple>,Long> joinPathSizes, int depth) {
		
		String relation = joinEdge.getJoinNode().getNodeRelation().getRelation();
		String attributeName = schema.getRelations().get(relation.toUpperCase()).getAttributeNames().get(joinEdge.getRightJoinAttribute());
		String query = String.format(SELECT_WHERE_SQL_STATEMENT, relation, attributeName, "'"+tuple.getValues().get(joinEdge.getLeftJoinAttribute()).toString()+"'");
		
		// Run query to get all tuples in join
		GenericTableObject result = genericDAO.executeQuery(query);
		
		// Get one tuple to add to sample
		Tuple joinTuple = null;
		if (result != null) {
			if (result.getTable().size() == 0) {
				// no tuples
				return;
			} else if (result.getTable().size() == 1) {
				// only one tuple
				joinTuple = result.getTable().get(0);
			} else {
				// sample a tuple using reservoir sampling (reservoir is joinTuple)
				long weightSummed = 0;
				while (joinTuple == null) {
					for (Tuple tupleInJoin : result.getTable()) {
						long size;
						Triple<String,Integer,Tuple> key = new Triple<String,Integer,Tuple>(relation,depth,tupleInJoin);
						if (joinPathSizes.containsKey(key))
							size = joinPathSizes.get(key);
						else {
//							size = SamplingUtils.computeJoinPathSizeFromTuple(genericDAO, schema, tupleInJoin, joinEdge.getJoinNode());
//							System.out.println("a:"+size);
							size = SamplingUtils.computeJoinPathSizeFromTuple2(genericDAO, schema, tupleInJoin, joinEdge.getJoinNode(), depth, joinPathSizes);
//							System.out.println("b:"+size);
							joinPathSizes.put(key, size);
						}
						
						weightSummed += size;
						double p = (double)size / (double)weightSummed;
						if (randomGenerator.nextDouble() < p) {
							joinTuple = tupleInJoin;
						}
					}
				}
			}
		}
		
		// Apply modes and add literal to clause
		modeOperationsForTuple(joinTuple, clause, hashConstantToVariable, groupedModes.get(relation), ground);
		
		// Recursive call on node's children
		for (JoinEdge childJoinEdge : joinEdge.getJoinNode().getEdges()) {
			generateBottomClauseAux(genericDAO, schema, joinTuple, childJoinEdge, 
					groupedModes, hashConstantToVariable, randomGenerator, clause, ground, joinPathSizes, depth+1);
		}
	}
	
	private void modeOperationsForTuple(Tuple tuple, MyClause clause, 
			Map<String, String> hashConstantToVariable, 
			List<Mode> relationAttributeModes, boolean ground) {
		Set<String> usedModes = new HashSet<String>();
		for (Mode mode : relationAttributeModes) {
			if (ground) {
				if (usedModes.contains(mode.toGroundModeString())) {
					continue;
				}
				else {
					mode = mode.toGroundMode();
					usedModes.add(mode.toGroundModeString());
				}
			}
			
			Predicate newLiteral = createLiteralFromTuple(hashConstantToVariable, tuple, mode, false);
			
			// Do not add literal if it's exactly the same as head literal
			if (!newLiteral.equals(clause.getPositiveLiterals().get(0).getAtomicSentence())) {
				clause.addNegativeLiteral(newLiteral);
			}
		}
	}
	
	private void acyclicStreamSample(GenericDAO genericDAO, Schema schema, Tuple tuple, JoinEdge joinEdge, Random randomGenerator, List<Tuple> sample) {
		String relation = joinEdge.getJoinNode().getNodeRelation().getRelation().toUpperCase();
		String attributeName = schema.getRelations().get(relation).getAttributeNames().get(joinEdge.getRightJoinAttribute());
		String query = String.format(SELECT_WHERE_SQL_STATEMENT, relation, attributeName, "'"+tuple.getValues().get(joinEdge.getLeftJoinAttribute()).toString()+"'");
		
		// Run query to get all tuples in join
		GenericTableObject result = genericDAO.executeQuery(query);
		
		// Get one tuple to add to sample
		Tuple joinTuple = null;
		if (result != null) {
			if (result.getTable().size() == 0) {
				// no tuples
				return;
			} else if (result.getTable().size() == 1) {
				// only one tuple
				joinTuple = result.getTable().get(0);
			} else {
				// sample a tuple using reservoir sampling (reservoir is joinTuple)
				long weightSummed = 0;
				while (joinTuple == null) {
					for (Tuple tupleInJoin : result.getTable()) {
						long size = SamplingUtils.computeJoinPathSizeFromTuple(genericDAO, schema, tupleInJoin, joinEdge.getJoinNode());
						weightSummed += size;
						double p = (double)size / (double)weightSummed;
						if (randomGenerator.nextDouble() < p) {
							joinTuple = tupleInJoin;
						}
					}
				}
			}
		}
		
		// Add tuple to sample
		sample.add(joinTuple);
		
		// Recursive call on node's children
		for (JoinEdge childJoinEdge : joinEdge.getJoinNode().getEdges()) {
			acyclicStreamSample(genericDAO, schema, joinTuple, childJoinEdge, randomGenerator, sample);
		}
	}
	
	/*
	 * Creates a literal from a tuple and a mode.
	 */
	private Predicate createLiteralFromTuple(Map<String, String> hashConstantToVariable, Tuple tuple, Mode mode, boolean headMode) {
		List<Term> terms = new ArrayList<Term>();
		for (int i = 0; i < mode.getArguments().size(); i++) {
			String value = tuple.getValues().get(i).toString();

			if (mode.getArguments().get(i).getIdentifierType().equals(IdentifierType.CONSTANT)) {
				terms.add(new Constant("\"" + value + "\""));
			} else {
				// INPUT or OUTPUT type
				if (!hashConstantToVariable.containsKey(value)) {
					String var = Commons.newVariable(varCounter);
					varCounter++;

					hashConstantToVariable.put(value, var);
				}
				terms.add(new Variable(hashConstantToVariable.get(value)));
			}
		}

		Predicate literal = new Predicate(mode.getPredicateName(), terms);
		return literal;
	}
}
