package castor.algorithms.bottomclause;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import aima.core.logic.fol.parsing.ast.Constant;
import aima.core.logic.fol.parsing.ast.Predicate;
import aima.core.logic.fol.parsing.ast.Term;
import aima.core.logic.fol.parsing.ast.Variable;
import aima.core.util.datastructure.Pair;
import castor.dataaccess.db.GenericDAO;
import castor.dataaccess.db.GenericTableObject;
import castor.hypotheses.MyClause;
import castor.language.IdentifierType;
import castor.language.InclusionDependency;
import castor.language.Mode;
import castor.language.Schema;
import castor.language.Tuple;
import castor.utils.Commons;

public class BottomClauseGeneratorOriginalAlgorithm {

	private static final String SELECTIN_SQL_STATEMENT = "SELECT * FROM %s WHERE %s IN %s;";

	private int varCounter;

	public BottomClauseGeneratorOriginalAlgorithm() {
		varCounter = 0;
	}

	/*
	 * Generate bottom clause for one example
	 */
	public MyClause generateBottomClause(GenericDAO genericDAO, Tuple exampleTuple, Schema schema, Mode modeH,
			List<Mode> modesB, int iterations, int recall, boolean applyInds) {
		Map<String, String> hashConstantToVariable = new HashMap<String, String>();
		Map<String, String> hashVariableToConstant = new HashMap<String, String>();
		return this.generateBottomClauseOneQueryPerRelationAttribute(genericDAO, hashConstantToVariable,
				hashVariableToConstant, exampleTuple, schema, modeH, modesB, iterations, recall, applyInds);
	}
	
	/*
	 * Generate ground bottom clause for one example
	 */
	public MyClause generateGroundBottomClause(GenericDAO genericDAO, Tuple exampleTuple, Schema schema, Mode modeH,
			List<Mode> modesB, int iterations, int recall, boolean applyInds) {
		
		// Keep only ground modes
		Mode groundModeH = modeH.toGroundMode();
		// Must keep order
		Set<Mode> groundModesB = new LinkedHashSet<Mode>();
		for (Mode mode : modesB) {
			Mode groundMode = mode.toGroundMode();
			groundModesB.add(groundMode);
		}
		List<Mode> groundModesBList = new LinkedList<Mode>(groundModesB);
		
		Map<String, String> hashConstantToVariable = new HashMap<String, String>();
		Map<String, String> hashVariableToConstant = new HashMap<String, String>();
		return this.generateBottomClauseOneQueryPerRelationAttribute(genericDAO, hashConstantToVariable,
				hashVariableToConstant, exampleTuple, schema, groundModeH, groundModesBList, iterations, recall, applyInds);
	}

	/*
	 * Generate bottom clause for each input example in examples list Reuses hash
	 * function to keep consistency between variable associations
	 */
	public List<MyClause> generateBottomClauses(GenericDAO genericDAO, List<Tuple> examples, Schema schema, Mode modeH,
			List<Mode> modesB, int iterations, int recall, boolean applyInds) {
		List<MyClause> bottomClauses = new LinkedList<MyClause>();
		Map<String, String> hashConstantToVariable = new HashMap<String, String>();
		Map<String, String> hashVariableToConstant = new HashMap<String, String>();
		for (Tuple example : examples) {
			bottomClauses.add(this.generateBottomClauseOneQueryPerRelationAttribute(genericDAO, hashConstantToVariable,
					hashVariableToConstant, example, schema, modeH, modesB, iterations, recall, applyInds));
		}
		return bottomClauses;
	}

	/*
	 * Bottom clause generation as described in original algorithm Queries database
	 * only once per relation-input_attribute
	 */
	public MyClause generateBottomClauseOneQueryPerRelationAttribute(GenericDAO genericDAO,
			Map<String, String> hashConstantToVariable, Map<String, String> hashVariableToConstant, Tuple exampleTuple,
			Schema schema, Mode modeH, List<Mode> modesB, int iterations, int recall, boolean applyInds) {
		MyClause clause = new MyClause();
		Map<String, Set<String>> inTerms = new HashMap<String, Set<String>>();

		// Check that arities of example and modeH match
		if (modeH.getArguments().size() != exampleTuple.getValues().size()) {
			throw new IllegalArgumentException("Example arity does not match modeH arity.");
		}

		// Create head literal
		varCounter = 0;
		Predicate headLiteral = createLiteralFromTuple(hashConstantToVariable, hashVariableToConstant, exampleTuple,
				modeH, inTerms);
		clause.addPositiveLiteral(headLiteral);

		// Group modes by relation name - input attribute position
		Map<Pair<String, Integer>, List<Mode>> groupedModes = new LinkedHashMap<Pair<String, Integer>, List<Mode>>();
		for (Mode mode : modesB) {
			// Get name of input attribute
			int inputVarPosition = 0;
			for (int i = 0; i < mode.getArguments().size(); i++) {
				if (mode.getArguments().get(i).getIdentifierType().equals(IdentifierType.INPUT)) {
					inputVarPosition = i;
					break;
				}
			}

			Pair<String, Integer> key = new Pair<String, Integer>(mode.getPredicateName(), inputVarPosition);
			if (!groupedModes.containsKey(key)) {
				groupedModes.put(key, new LinkedList<Mode>());
			}
			groupedModes.get(key).add(mode);
		}

		// Create body literals
		for (int j = 0; j < iterations; j++) {

			// Create new inTerms to hold terms for this iteration
			Map<String, Set<String>> newInTerms = new HashMap<String, Set<String>>();

			Iterator<Entry<Pair<String, Integer>, List<Mode>>> groupedModesIterator = groupedModes.entrySet()
					.iterator();
			while (groupedModesIterator.hasNext()) {
				Entry<Pair<String, Integer>, List<Mode>> entry = groupedModesIterator.next();
				String relationName = entry.getKey().getFirst();
				int inputVarPosition = entry.getKey().getSecond();
				List<Mode> relationAttributeModes = entry.getValue();

				// Get input attribute name
				String attributeName = schema.getRelations().get(relationName.toUpperCase()).getAttributeNames()
						.get(inputVarPosition);

				// Get all attribute types for input attribute
				Set<String> attributeTypes = new HashSet<String>();
				for (Mode mode : relationAttributeModes) {
					attributeTypes.add(mode.getArguments().get(inputVarPosition).getType());
				}

				// Get known terms for all attribute types
				boolean seenType = false;
				Set<String> knownTermsSet = new HashSet<String>();
				for (String type : attributeTypes) {
					if (inTerms.containsKey(type)) {
						seenType = true;
						knownTermsSet.addAll(inTerms.get(type));
						break;
					}
				}
				// If there is no list of known terms for attributeType, skip mode
				if (!seenType) {
					continue;
				}
				String knownTerms = toListString(knownTermsSet);

				// Generate new literals for grouped modes
				List<Predicate> newLiterals = operationForGroupedModes(genericDAO, schema, clause,
						hashConstantToVariable, hashVariableToConstant, newInTerms, relationName, attributeName,
						relationAttributeModes, groupedModes, knownTerms, recall);

				// Apply INDs
				if (applyInds) {
					followIndChain(genericDAO, schema, clause, newLiterals, hashConstantToVariable,
							hashVariableToConstant, newInTerms, groupedModes, recall, relationName,
							new HashSet<String>());
				}
				for (Predicate literal : newLiterals) {
					clause.addNegativeLiteral(literal);
				}
			}

			// Add new terms to inTerms
			Iterator<Map.Entry<String, Set<String>>> newInTermsIterator = newInTerms.entrySet().iterator();
			while (newInTermsIterator.hasNext()) {
				Map.Entry<String, Set<String>> pair = (Map.Entry<String, Set<String>>) newInTermsIterator.next();
				String variableType = pair.getKey();
				if (!inTerms.containsKey(variableType)) {
					inTerms.put(variableType, new HashSet<String>());
				}
				inTerms.get(variableType).addAll(newInTerms.get(variableType));
			}
		}

		return clause;
	}

	private List<Predicate> operationForGroupedModes(GenericDAO genericDAO, Schema schema, MyClause clause,
			Map<String, String> hashConstantToVariable, Map<String, String> hashVariableToConstant,
			Map<String, Set<String>> newInTerms, String relationName, String attributeName,
			List<Mode> relationAttributeModes, Map<Pair<String, Integer>, List<Mode>> groupedModes, String knownTerms,
			int recall) {
		List<Predicate> newLiterals = new LinkedList<Predicate>();

		// Create query and run
		String query = String.format(SELECTIN_SQL_STATEMENT, relationName, attributeName, knownTerms);
		GenericTableObject result = genericDAO.executeQuery(query);

		if (result != null) {
			for (Mode mode : relationAttributeModes) {
				int solutionsCounter = 0;
				for (Tuple tuple : result.getTable()) {

					if (solutionsCounter >= recall)
						break;

					Predicate literal = createLiteralFromTuple(hashConstantToVariable, hashVariableToConstant, tuple,
							mode, newInTerms);
					addNotRepeated(newLiterals, literal);
					solutionsCounter++;
				}
			}

		}

		return newLiterals;
	}

	private Predicate createLiteralFromTuple(Map<String, String> hashConstantToVariable,
			Map<String, String> hashVariableToConstant, Tuple tuple, Mode mode, Map<String, Set<String>> inTerms) {
		List<Term> terms = new ArrayList<Term>();
		for (int i = 0; i < mode.getArguments().size(); i++) {
			String value = tuple.getValues().get(i);

			if (mode.getArguments().get(i).getIdentifierType().equals(IdentifierType.CONSTANT)) {
				terms.add(new Constant("\"" + value + "\""));
			} else {
				// INPUT or OUTPUT type
				if (!hashConstantToVariable.containsKey(value)) {
					String var = Commons.newVariable(varCounter);
					varCounter++;

					hashConstantToVariable.put(value, var);
					hashVariableToConstant.put(var, value);
				}
				terms.add(new Variable(hashConstantToVariable.get(value)));
			}
			// Add constants to inTerms
			if (mode.getArguments().get(i).getIdentifierType().equals(IdentifierType.INPUT)) {
				String variableType = mode.getArguments().get(i).getType();
				if (!inTerms.containsKey(variableType)) {
					inTerms.put(variableType, new HashSet<String>());
				}
				inTerms.get(variableType).add(value);
			}
		}
		Predicate literal = new Predicate(mode.getPredicateName(), terms);
		return literal;
	}

	private void followIndChain(GenericDAO genericDAO, Schema schema, MyClause clause,
			List<Predicate> newLiteralsForGroupedModes, Map<String, String> hashConstantToVariable,
			Map<String, String> hashVariableToConstant, Map<String, Set<String>> newInTerms,
			Map<Pair<String, Integer>, List<Mode>> groupedModes, int recall, String currentPredicate,
			Set<String> seenPredicates) {

		if (!seenPredicates.contains(currentPredicate)
				&& schema.getInclusionDependencies().containsKey(currentPredicate)) {
			for (InclusionDependency ind : schema.getInclusionDependencies().get(currentPredicate)) {

				if (!seenPredicates.contains(ind.getRightPredicateName())) {
					// Apply IND
					List<Predicate> literalsFromInd = applyInclusionDependency(genericDAO, schema, clause,
							newLiteralsForGroupedModes, hashConstantToVariable, hashVariableToConstant, newInTerms, ind,
							groupedModes, recall);
					addNotRepeated(newLiteralsForGroupedModes, literalsFromInd);

					// Add current predicate to seen list
					seenPredicates.add(currentPredicate);

					// Follow chain
					followIndChain(genericDAO, schema, clause, newLiteralsForGroupedModes, hashConstantToVariable,
							hashVariableToConstant, newInTerms, groupedModes, recall, ind.getRightPredicateName(),
							seenPredicates);
				}
			}
		}
	}

	private List<Predicate> applyInclusionDependency(GenericDAO genericDAO, Schema schema, MyClause clause,
			List<Predicate> newLiteralsForGroupedModes, Map<String, String> hashConstantToVariable,
			Map<String, String> hashVariableToConstant, Map<String, Set<String>> newInTerms, InclusionDependency ind,
			Map<Pair<String, Integer>, List<Mode>> groupedModes, int recall) {

		List<Predicate> newLiterals = new LinkedList<Predicate>();

		// For each literal with predicate equal to leftIndPredicate, check if IND holds
		for (int i = 0; i < newLiteralsForGroupedModes.size(); i++) {
			Predicate literal = newLiteralsForGroupedModes.get(i);
			String predicate = literal.getPredicateName();

			if (predicate.equals(ind.getLeftPredicateName())) {
				Term leftIndTerm = literal.getArgs().get(ind.getLeftAttributeNumber());

				// Check if IND is satisfied
				boolean indSatisfied = false;
				for (int j = 0; j < clause.getNegativeLiterals().size(); j++) {
					Predicate otherLiteral = (Predicate) clause.getNegativeLiterals().get(j).getAtomicSentence();
					String otherPredicate = otherLiteral.getPredicateName();

					if (otherPredicate.equals(ind.getRightPredicateName())) {
						Term rightIndTerm = otherLiteral.getArgs().get(ind.getRightAttributeNumber());

						if (leftIndTerm.equals(rightIndTerm)) {
							// IND satisfied
							indSatisfied = true;
							break;
						}
					}
				}

				// If IND is not satisfied, add literals that make IND satisfied
				if (!indSatisfied) {
					// Get constant value
					String value;
					if (Commons.isVariable(leftIndTerm)) {
						value = hashVariableToConstant.get(leftIndTerm.getSymbolicName());
					} else {
						// value = leftIndTerm.substring(1, leftIndTerm.length()-1);
						value = leftIndTerm.getSymbolicName();
					}
					Set<String> termsSet = new HashSet<String>();
					termsSet.add(value);
					String knownTerms = toListString(termsSet);

					// Get modes for relation-attribute
					String relationName = ind.getRightPredicateName();
					String attributeName = schema.getRelations().get(relationName.toUpperCase()).getAttributeNames()
							.get(ind.getRightAttributeNumber());
					Pair<String, Integer> key = new Pair<String, Integer>(ind.getRightPredicateName(),
							ind.getRightAttributeNumber());
					List<Mode> relationAttributeModes = groupedModes.get(key);

					// Generate new literals
					List<Predicate> modeBLiterals = operationForGroupedModes(genericDAO, schema, clause,
							hashConstantToVariable, hashVariableToConstant, newInTerms, relationName, attributeName,
							relationAttributeModes, groupedModes, knownTerms, recall);
					addNotRepeated(newLiterals, modeBLiterals);
				}
			}
		}
		return newLiterals;
	}

	/*
	 * Add non-repeated newLiterals to literals
	 */
	private void addNotRepeated(List<Predicate> literals, List<Predicate> newLiterals) {
		for (Predicate newLiteral : newLiterals) {
			if (!literals.contains(newLiteral)) {
				literals.add(newLiteral);
			}
		}
	}

	private void addNotRepeated(List<Predicate> literals, Predicate newLiteral) {
		if (!literals.contains(newLiteral)) {
			literals.add(newLiteral);
		}
	}

	/*
	 * Bottom clause generation as described in original algorithm Assumes that
	 * exampleTuple is for relation with same name as modeH
	 */
	public MyClause generateBottomClause(GenericDAO genericDAO, Map<String, String> hashConstantToVariable,
			Map<String, String> hashVariableToConstant, Tuple exampleTuple, Schema schema, Mode modeH,
			List<Mode> modesB, int iterations, int recall) {
		MyClause clause = new MyClause();
		Map<String, Set<String>> inTerms = new HashMap<String, Set<String>>();

		// Check that arities of example and modeH match
		if (modeH.getArguments().size() != exampleTuple.getValues().size()) {
			throw new IllegalArgumentException("Example arity does not match modeH arity.");
		}

		// Create head literal
		varCounter = 0;
		Predicate headLiteral = createLiteralFromTuple(hashConstantToVariable, hashVariableToConstant, exampleTuple,
				modeH, inTerms);
		clause.addPositiveLiteral(headLiteral);

		// Create body literals
		for (int j = 0; j < iterations; j++) {

			// Create new inTerms to hold terms for this iteration
			Map<String, Set<String>> newInTerms = new HashMap<String, Set<String>>();

			for (Mode mode : modesB) {
				// Find position of input variable
				int inputVarPosition = 0;
				for (int i = 0; i < mode.getArguments().size(); i++) {
					if (mode.getArguments().get(i).getIdentifierType().equals(IdentifierType.INPUT)) {
						inputVarPosition = i;
						break;
					}
				}

				String attributeName = schema.getRelations().get(mode.getPredicateName().toUpperCase())
						.getAttributeNames().get(inputVarPosition);
				String attributeType = mode.getArguments().get(inputVarPosition).getType();

				// If there is no list of known terms for attributeType, skip mode
				if (!inTerms.containsKey(attributeType)) {
					continue;
				}
				String knownTerms = toListString(inTerms.get(attributeType));

				// Create query and run
				String query = String.format(SELECTIN_SQL_STATEMENT, mode.getPredicateName(), attributeName,
						knownTerms);
				GenericTableObject result = genericDAO.executeQuery(query);

				if (result != null) {
					int solutionsCounter = 0;
					for (Tuple tuple : result.getTable()) {

						if (solutionsCounter >= recall)
							break;

						Predicate literal = createLiteralFromTuple(hashConstantToVariable, hashVariableToConstant,
								tuple, mode, newInTerms);
						clause.addNegativeLiteral(literal);
						solutionsCounter++;
					}
				}
			}

			// Add new terms to inTerms
			Iterator<Map.Entry<String, Set<String>>> newInTermsIterator = newInTerms.entrySet().iterator();
			while (newInTermsIterator.hasNext()) {
				Map.Entry<String, Set<String>> pair = (Map.Entry<String, Set<String>>) newInTermsIterator.next();
				String variableType = pair.getKey();
				if (!inTerms.containsKey(variableType)) {
					inTerms.put(variableType, new HashSet<String>());
				}
				inTerms.get(variableType).addAll(newInTerms.get(variableType));
			}
		}

		return clause;
	}

	/*
	 * Convert set to string "('item1','item2',...)"
	 */
	private String toListString(Set<String> terms) {
		StringBuilder builder = new StringBuilder();
		builder.append("(");
		int counter = 0;
		for (String term : terms) {
			builder.append("'" + term + "'");
			if (counter < terms.size() - 1) {
				builder.append(",");
			}
			counter++;
		}
		builder.append(")");
		return builder.toString();
	}
}
