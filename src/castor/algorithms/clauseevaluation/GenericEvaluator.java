package castor.algorithms.clauseevaluation;

import java.util.List;

import castor.algorithms.coverageengines.CoverageEngine;
import castor.db.dataaccess.GenericDAO;
import castor.hypotheses.ClauseInfo;
import castor.language.Relation;
import castor.language.Schema;
import castor.language.Tuple;
import castor.utils.TimeKeeper;
import castor.utils.TimeWatch;

public class GenericEvaluator implements ClauseEvaluator {

	public  double computeScore(GenericDAO genericDAO, CoverageEngine coverageEngine, Schema schema, List<Tuple> remainingPosExamples, Relation posExamplesRelation, Relation negExamplesRelation, ClauseInfo clauseInfo, EvaluationFunctions.FUNCTION evaluationFunction) {
		TimeWatch tw = TimeWatch.start();
		
		if (clauseInfo.getScore() == null) {
			// Get new positive examples covered
			// Adding 1 to count seed example
			int newPosTotal = remainingPosExamples.size() + 1;
			int newPosCoveredCount = coverageEngine.countCoveredExamplesFromList(genericDAO, schema, clauseInfo, remainingPosExamples, posExamplesRelation, true) + 1;

			// Get total negative examples covered
			int totalNeg = coverageEngine.getAllNegExamples().size();
			int negCoveredCount = coverageEngine.countCoveredExamplesFromRelation(genericDAO, schema, clauseInfo, negExamplesRelation, false);
			
			// Compute statistics
			int truePositive = newPosCoveredCount;
			int falsePositive = negCoveredCount;
			int trueNegative = totalNeg - negCoveredCount;
			int falseNegative = newPosTotal - newPosCoveredCount;
			
			// Compute score
			double score = EvaluationFunctions.score(evaluationFunction, truePositive, falsePositive, trueNegative, falseNegative);
			clauseInfo.setScore(score);
		}
		
		TimeKeeper.scoringTime += tw.time();
		return clauseInfo.getScore();
	}
	
	public boolean entails(GenericDAO genericDAO, CoverageEngine coverageEngine, Schema schema, ClauseInfo clauseInfo, Tuple exampleTuple, Relation posExamplesRelation) {
		TimeWatch tw = TimeWatch.start();
		boolean entails = coverageEngine.entails(genericDAO, schema, clauseInfo, exampleTuple, posExamplesRelation, true);
		TimeKeeper.entailmentTime += tw.time();
		return entails;
	}
}
