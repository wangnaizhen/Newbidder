package com.jacamars.dsp.rtb.common;

import java.io.Serializable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.hash.BloomFilter;
import com.jacamars.dsp.crosstalk.budget.CrosstalkConfig;
import com.jacamars.dsp.rtb.bidder.RTBServer;
import com.jacamars.dsp.rtb.blocks.Bloom;
import com.jacamars.dsp.rtb.blocks.LookingGlass;
import com.jacamars.dsp.rtb.blocks.NavMap;
import com.jacamars.dsp.rtb.blocks.SimpleSet;
import com.jacamars.dsp.rtb.pojo.BidRequest;
import com.jacamars.dsp.rtb.pojo.Impression;
import com.jacamars.dsp.rtb.probe.Probe;
import com.jacamars.dsp.rtb.tools.JdbcTools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

/**
 * A class that implements a parse-able node in the RTB object, and applies
 * campaign logic. The idea of the node is to define a constraint using the
 * dotted form of the JSON specification of the bid request parameter. By
 * default if you specify a Node and the hierarchy does not exist in the bid
 * request, then this means the campaign does not match. You can override this
 * behavior by setting the object's 'notPresentOk' flag. Then when the hierarchy
 * doesn;t exist, the Node tests true otherwise if it is present, then the Node
 * returns the value of the comparison that was specified.
 * <p>
 * Examples Hierarchies:
 * <p>
 * Retrieve a value: 'user.geo.country' - this means the Node will extract this
 * field from the bid request.
 * <p>
 * Retrieve a value from a list: 'imp.0.id' is equivalent to JS: value =
 * imp[0].id;
 * <p>
 * You also specify what to compare the bid request's values to. In could be
 * that the return values are an array, or maybe just a scalar. The Node can
 * handle both data types.
 * <p>
 * The comparison operators are equal, not equal, lt, le, gt, ge Member of set,
 * not member of set, set intersects, not set intersects, geo in range lat/lon,
 * not in range of lat/lon, in the domain of a range of numbers, and not in the
 * range of numbers.
 * <p>
 * TODO: Query
 * 
 * @author Ben M. Faul
 *
 */
public class Node implements Serializable {
	/** Keeps up with overlapping error messages */
	static volatile Map<String, Long> errors = new ConcurrentHashMap<String, Long>();
	/** Logging object */
	protected static final Logger logger = LoggerFactory.getLogger(Node.class);

	public static Map<String, Map> builtinMap = new HashMap();

	/** Counts number of times it has been judged FALSE */
	transient AtomicLong falseCount = new AtomicLong(0);

	static {
		Map map = new HashMap();
		List list = new ArrayList();
		list.add(100);
		list.add(200);
		list.add(300);
		map.put("3456", 1);
		map.put("ben", 1);
		map.put("peter", 2);
		map.put("clarissa", "hello");
		map.put("list", list);
		builtinMap.put("test", map);
	}

	Set qvalue = null;
	
	public int id = 0;

	boolean testit = false;
	/** Query TBD */
	public static final int QUERY = 0;
	/* Test for equality */
	public static final int EQUALS = 1;
	/* Test for inequality */
	public static final int NOT_EQUALS = 2;
	/** Test for set membership */
	public static final int MEMBER = 3;
	/** Test for not membership */
	public static final int NOT_MEMBER = 4;
	/** Test set intersection */
	public static final int INTERSECTS = 5;
	/** Test not intersection */
	public static final int NOT_INTERSECTS = 6;
	/** Test lat/lon range with other lat/lon, in km */
	public static final int INRANGE = 7;
	/** Test not in range */
	public static final int NOT_INRANGE = 8;
	/** Test less than numeric */
	public static final int LESS_THAN = 9;
	/** Test less than equal numeric */
	public static final int LESS_THAN_EQUALS = 10;
	/** Test greater than numeric */
	public static final int GREATER_THAN = 11;
	/** Test greater than equal numeric */
	public static final int GREATER_THAN_EQUALS = 12;
	/** Test in domain x less than y greater than z */
	public static final int DOMAIN = 13;
	/** Test not in domain */
	public static final int NOT_DOMAIN = 14;
	/** Test the string is a substring of another */
	public static final int STRINGIN = 15;
	/** Test the string not a substring in another */
	public static final int NOT_STRINGIN = 16;
	/** Does an attribute exist in the rtb request */
	public static final int EXISTS = 17;
	/** Does an attribute not exist in the rtb reqest */
	public static final int NOT_EXISTS = 18;
	/** OR operator */
	public static final int OR = 19;
	/** Is the string REGEX'ed? */
	public static final int REGEX = 20;
	/** Not in the REGEX */
	public static final int NOT_REGEX = 21;
	/** LIVERAMP OPERATIONS */
	public static final int IDL = 22;
	public static final int NOT_IDL = 23;
	
	/** If this node contains geo information, it will be found here */
	transient List<Point> points = new ArrayList<Point>();
	/**
	 * A convenient map to turn string operator references to their int conterparts
	 */
	public static Map<String, Integer> OPS = new HashMap();
	static {
		OPS.put("QUERY", QUERY);
		OPS.put("EQUALS", EQUALS);
		OPS.put("NOT_EQUALS", NOT_EQUALS);
		OPS.put("MEMBER", MEMBER);
		OPS.put("NOT_MEMBER", NOT_MEMBER);
		OPS.put("INTERSECTS", INTERSECTS);
		OPS.put("NOT_INTERSECTS", NOT_INTERSECTS);
		OPS.put("INRANGE", INRANGE);
		OPS.put("NOT_INRANGE", NOT_INRANGE);
		OPS.put("LESS_THAN", LESS_THAN);
		OPS.put("LESS_THAN_EQUALS", LESS_THAN_EQUALS);
		OPS.put("GREATER_THAN", GREATER_THAN);
		OPS.put("GREATER_THAN_EQUALS", GREATER_THAN_EQUALS);
		OPS.put("DOMAIN", DOMAIN);
		OPS.put("NOT_DOMAIN", NOT_DOMAIN);
		OPS.put("STRINGIN", STRINGIN);
		OPS.put("NOT_STRINGIN", NOT_STRINGIN);
		OPS.put("EXISTS", EXISTS);
		OPS.put("NOT_EXISTS", NOT_EXISTS);
		OPS.put("OR", OR);
		OPS.put("REGEX", REGEX);
		OPS.put("NOT_REGEX", NOT_REGEX);
		OPS.put("IDL", IDL);
		OPS.put("NOT_IDL", NOT_IDL);
		
	}

	public static List<String> OPNAMES = new ArrayList<String>();
	static {
		OPNAMES.add("QUERY");
		OPNAMES.add("EQUALS");
		OPNAMES.add("NOT_EQUALS");
		OPNAMES.add("MEMBER");
		OPNAMES.add("NOT_MEMBER");
		OPNAMES.add("INTERSECTS");
		OPNAMES.add("NOT_INTERSECTS");
		OPNAMES.add("INRANGE");
		OPNAMES.add("IDL");
		OPNAMES.add("NOT_IDL");
		OPNAMES.add("NOT_INRANGE");
		OPNAMES.add("LESS_THAN");
		OPNAMES.add("LESS_THAN_EQUALS");
		OPNAMES.add("GREATER_THAN");
		OPNAMES.add("GREATER_THAN_EQUALS");
		OPNAMES.add("DOMAIN");
		OPNAMES.add("NOT_DOMAIN");
		OPNAMES.add("STRINGIN");
		OPNAMES.add("NOT_STRINGIN");
		OPNAMES.add("EXISTS");
		OPNAMES.add("NOT_EXISTS");
		OPNAMES.add("OR");
		OPNAMES.add("REGEX");
		OPNAMES.add("NOT_REGEX");
	}

	public String customer_id;
	/** campaign identifier */
	public String name;
	/** dotted form of the item in the bid to pull (eg user.geo.lat) */
	public String hierarchy;
	
	public String operand;
	public String operand_type;
	public String operand_ordinal;
	
	/** which operator to use */
	transient public int operator = -1;
	/** the sub operator if operator is query */
	transient public int suboperator = -1;
	/** Node's value as an object. */
	public Object value;
	/** Node's value as a map */
	transient Map mvalue;
	/** The retrieved object from the bid, as defined in the hierarchy */
	transient protected Object brValue;

	/** when the value is a number */
	transient Number ival = null;
	/** when the value is a string */
	transient String sval = null;
	/** when the value is a set */
	transient Set qval = null;
	/** when the value is a map */
	transient Map mval = null;
	/** When the value is a list */
	transient List lval = null;

	/** if present will execute this JavaScript code */
	protected String code = null;
	/** context to execute in */
	public JJS shell = null;
	/** text name of the operator */
	public String op;
	/** text name of the query sub op */
	/** description */
	String description;
	transient String subop = null;
	/** A pattern matcher */
	transient Pattern pattern;

	/** set to false if required field not present */
	public boolean notPresentOk = true;
	/** decomposed hierarchy */
	public List<String> bidRequestValues = new ArrayList<String>();

	public static Node getInstance(int id) throws Exception {
		String select = "select * from rtb_standards where id="+id;
		var conn = CrosstalkConfig.getInstance().getConnection();
		var stmt = conn.createStatement();
		var prep = conn.prepareStatement(select);
		ResultSet rs = prep.executeQuery();
		
		ArrayNode inner = JdbcTools.convertToJson(rs);
		ObjectNode y = (ObjectNode) inner.get(0);
		return new Node(y);	
	}
	/**
	 * Simple constructor useful for testing.
	 */
	public Node() {

	}
	
	public Node(JsonNode n) throws Exception {
		 customer_id = n.get("customer_id").asText();
		 id = n.get("id").asInt();
		 hierarchy = n.get("rtbspecification").asText();
		 if (n.get("operator") != null)
			 op = n.get("operator").asText().toUpperCase();
		 else
			 op = n.get("op").asText().toUpperCase();
		 
		 if ((op.equals("IDL") || op.equals("NOT IDL")) && hierarchy == null || hierarchy.length()==0)
			 hierarchy = "user.ext.eids";
			 
		 operand = n.get("operand").asText("");
		 operand_type = n.get("operand_type").asText("integer");
		 
		 operand_ordinal = n.get("operand_ordinal").asText("scalar");
		 
		 if (operand_ordinal.equalsIgnoreCase("scalar")) {
			 switch(operand_type.toLowerCase()) {
			 case "double":
				 if (operand.equals(""))
					 operand = "0";
				 value = Double.parseDouble(operand);
				 break;
			 case "int":
			 case "integer":
				 if (operand.equals(""))
					 operand = "0";
				 value = Integer.parseInt(operand);
				 break;
			 case "string":
				 value = operand;
				 break;
			 }
		 } else {
			 String [] v = operand.split(",");
			 value = new Object[v.length];
			 switch(operand_type.toLowerCase()) {
			 case "double":
				var list = new ArrayList<Double>();
				value = list;
			 	for (int i=0;i<v.length;i++) {
			 		list.add(Double.parseDouble(v[i]));
			 	}
			 	break;
			 case "int":
			 case "integer":
				 value = new Integer[v.length];
				 for (int i=0;i<v.length;i++) {
				 		((Integer[])value)[i] = Integer.parseInt(v[i]);
				 	}
				 break;
			 case "string":
				 value = new String[v.length];
				 for (int i=0;i<v.length;i++) {
					if (v[i] == null)
						 v[i] = "";
				 	((String[])value)[i] = v[i];
				 }
				 break;
			 }
		 }
		 
		 if (n.get("rtb_required") != null)
			 notPresentOk = n.get("rtb_required").asBoolean();
		 else
			 notPresentOk = n.get("notPresentOk").asBoolean();
		 name  = n.get("name").asText();
		 if (n.get("description") != null)
			 description = n.get("description").asText(); 
		 
		setBRvalues();
		setValues();
	}
	
	public static PreparedStatement toSql(Node n, Connection conn) throws Exception {
		if (n.id == 0) 
			return doNew(n, conn);
		return doUpdate(n, conn);
	}
	
	static PreparedStatement doNew(Node n, Connection conn) throws Exception {
		PreparedStatement p = null;
		String sql = "INSERT INTO rtb_standards (" 
		 +"rtbspecification,"
		 +"operator,"
		 +"operand,"
		 +"operand_type,"
		 +"operand_ordinal,"
		 +"rtb_required,"
		 +"name,"
		 +"customer_id,"
		 +"description) VALUES("
		 +"?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
		
		p = conn.prepareStatement(sql);
		
		String str = getValue(n);
		String type = getType(n);
		String ord = getOrdinal(n);
		
		p.setString(1, n.hierarchy);
		p.setString(2, n.op);
		p.setString(3,str);
		p.setString(4,type);
		p.setString(5, ord);
		if (n.notPresentOk)
			p.setInt(6,1);
		else
			p.setInt(6, 0);
		p.setString(7, n.name);
		p.setString(8, n.customer_id);
		p.setString(9, n.description);;
		
		return p;
	}
	
	static String getValue(Node n) {
		String str = "";
		if (n.value instanceof Object[]) {
			Object[] list = (Object[])n.value;
			for (int i=0; i<list.length;i++) {
				str += list[i];
				if (i+1 < list.length)
					str += ",";
			}
		} if (n.value instanceof List) {
			for (Object x : (List)n.value) {
				str +=  x + ",";
			}
			str = str.substring(0,str.length()-1);
		} else
			str = "" + n.value;
		return str;
	}
	
	static String getType(Node n) {
		Object x = n.value;
		if (n.value instanceof Object[] || n.value instanceof List) {
			if (n.value instanceof List) 
				x = ((List)n.value).get(0);
			else
				x = ((Object[])n.value)[0];
		}
		if (x instanceof Double)
			return "double";
		if (x instanceof Integer)
			return "integer";
		if (x instanceof String)
			return "string";
		return "???";
	}
	
	static String getOrdinal(Node n) {
		Object x = n.value;
		if (n.value instanceof Object[] || n.value instanceof List)
			return "list";
		return "scalar";
	}
	
	static PreparedStatement doUpdate(Node n, Connection conn) throws Exception {
		PreparedStatement p = null;
		String sql = "UPDATE  rtb_standards SET " 
		 +"rtbspecification=?,"
		 +"operator=?,"
		 +"operand=?,"
		 +"operand_type=?,"
		 +"operand_ordinal=?,"
		 +"rtb_required=?,"
		 +"name=?,"
		 +"description=? WHERE id=?";
		
		p = conn.prepareStatement(sql);
			
		String str = getValue(n);
		String type = getType(n);
		String ord = getOrdinal(n);
		
		p.setString(1, n.hierarchy);
		p.setString(2, n.op);
		p.setString(3,str);
		p.setString(4,type);
		p.setString(5, ord);
		if (n.notPresentOk)
			p.setInt(6,1);
		else
			p.setInt(6, 0);
		p.setString(7, n.name);
		p.setString(8, n.description);
		p.setInt(9,n.id);

		return p;
	}
	

	/**
	 * Get the false count for this node
	 * 
	 * @return long. The current false count
	 */
	@JsonIgnore
	public long getFalseCount() {
		return falseCount.get();
	}

	/**
	 * Set the false count to 0
	 */
	public void clearFalseCount() {
		falseCount.set(0);
	}

	/**
	 * Constructor for the campaign Node
	 * 
	 * @param name      String. The name of this node.
	 * @param hierarchy String . The hierarchy in the request associated with this
	 *                  node.
	 * @param operator  int. The operator to apply to this operation.
	 * @param value     Object. The constant to test the value of the hierarchy
	 *                  against.
	 * @throws Exception if the values obejct is not recognized.
	 */
	public Node(String name, String hierarchy, String operator, Object value) throws Exception {

		this.name = name;
		this.hierarchy = hierarchy;
		op = operator;
		this.value = value;

		setBRvalues();
		setValues();

	}

	public Node(Map map) throws Exception {
		this.name = (String) map.get("name");
		this.customer_id = (String)map.get("customer_id");
		Object test = map.get("op");
		op = (String) test;
		test = map.get("notPresentOk");
		if (test != null)
			test = (Boolean) test;
		value = map.get("value");
		List brv = (List) map.get("bidRequestValues");
		hierarchy = "";
		for (int i = 0; i < brv.size() - 1; i++) {
			hierarchy += brv.get(i);
			hierarchy += ".";
		}
		hierarchy += brv.get(brv.size() - 1);

		setBRvalues();
		setValues();
	}

	/**
	 * Sets the values from the this.value object.
	 * 
	 * @throws Exception if this.values is not a recognized object.
	 */
	public void setValues() throws Exception {
		if (op != null) {
			Integer x = OPS.get(op);
			if (x == null)
				throw new Exception("Unknown operator: " + op);
			operator = x.intValue();
		}

		/**
		 * If its an array, connvert to a list. Passing in arrays screws up membership
		 * and set operations which expect lists
		 */

		if (value instanceof String[] || value instanceof int[] || value instanceof double[]) {
			List list = new ArrayList<Object>();
			Object[] x = (Object[]) value;
			for (Object o : x) {
				list.add(o);
			}
			value = list;
		}

		if (value instanceof Integer || value instanceof Double) {
			ival = (Number) value;
		}
		if (value instanceof TreeSet)
			qval = (TreeSet) value;
		if (value instanceof String)
			sval = (String) value;
		if (value instanceof Map)
			mval = (Map) value;
		if (value instanceof List) { // convert ints to doubles
			if (this.op.equals("OR") || this.operator == OR) {
				List x = (List) value;
				Node y = null;
				List newList = new ArrayList();
				for (int i = 0; i < x.size(); i++) {

					Object test = x.get(i);
					if (test instanceof LinkedHashMap) {
						y = new Node((Map) test);
					} else
						y = (Node) test;
					y.setValues();
					newList.add(y);
				}
				value = newList;
				lval = (List) value;
			} else if (this.op.equals("QUERY") || this.operator == QUERY) {
				List x = (List) value;
				String source = (String) x.get(0);
				String name = (String) x.get(1);
				subop = (String) x.get(2);
				Object operand = x.get(3);
				resetFromMap(operand);
				suboperator = OPS.get(subop);
				if (source.equals("builtin")) {
					value = builtinMap.get(name);
				} else { // Is Aerospike

				}
			} else
				lval = (List) value;
		}

		StringBuilder sh = new StringBuilder();
		for (int i = 0; i < bidRequestValues.size(); i++) {
			sh.append(bidRequestValues.get(i));
			if (i + 1 >= bidRequestValues.size() == false) {
				sh.append(".");
			}
		}

		/////////////////////////////////////// LAT LON STUFF ///////////////////////////////////////////////////
		if (op != null && (op.equals("INRANGE") || op.equals("NOT_INRANGE"))) {
			LookingGlass cz = (LookingGlass) LookingGlass.symbols.get("@ZIPCODES");
			if (value instanceof String) {
				String ref = (String)value;
				ref = ref.toUpperCase();
				String [] parts = ref.split(",");
				if (ref.startsWith("LAT")) {
					double range = Double.parseDouble(parts[parts.length-1].trim());
					for (int i=1; i<parts.length-1;i++) {
						double x = Double.parseDouble(parts[i].trim());
						double y = Double.parseDouble(parts[i+1].trim());
						Point p = new Point(x,y,range);
						points.add(p);
					}
				} else
				if (ref.startsWith("ZIP")) {
					for (int i=0; i<parts.length-1;i++) {
						double range = Double.parseDouble(parts[parts.length-1].trim());
						String [] lz = (String[])cz.query(parts[i].trim());
						double x = Double.parseDouble(lz[1].trim());
						double y = Double.parseDouble(lz[2].trim());
						Point p = new Point(x,y,range);
						points.add(p);
					}
				}
				
			} else {
				List<Double> list = (List)value;
				for (int i=0; i<list.size();i+=3) {
					double x = list.get(i);
					double y = list.get(i+1);
					double range = list.get(i+2);
					Point p = new Point(x,y,range);
					points.add(p);
				}
			}
		}
		/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

		
		hierarchy = sh.toString();
	}

	void resetFromMap(Object value) {
		if (value instanceof Integer || value instanceof Double) {
			ival = (Number) value;
		}
		if (value instanceof TreeSet)
			qval = (TreeSet) value;
		if (value instanceof String)
			sval = (String) value;
		if (value instanceof Map)
			mval = (Map) value;
		if (value instanceof List) { // convert ints to doubles
			lval = (List) value;
		}
	}

	/**
	 * Does this atrribute have this hierarchy
	 * 
	 * @param str String. The string to test.
	 * @return true if the hierarchy matches the string
	 */
	public boolean equals(String str) {
		if (hierarchy == null) {
			hierarchy = "";
			for (int i = 0; i < bidRequestValues.size(); i++) {
				hierarchy += bidRequestValues.get(i);
				if (i + 1 != bidRequestValues.size()) {
					hierarchy += ".";
				}
			}
		}
		return str.equals(hierarchy);
	}

	/**
	 * Constructor for campaign node without attached JavaScript code
	 * 
	 * @param name      String. The name of the node.
	 * @param heirarchy The dotted notation hierarchy associated with this node.
	 * @param operator  int. The operation to apply to the node.
	 * @param value     Object. The value that the bid request specified by
	 *                  hierarchy will be tested against.
	 * @throws Exception if the value object is not recognized.
	 */
	public Node(String name, String heirarchy, int operator, Object value) throws Exception {

		this(name, heirarchy, OPNAMES.get(operator), value); // fake this out so
																// we don't call
																// recursive
		this.operator = operator;
		// setValues();
		this.op = OPNAMES.get(operator);
	}

	/**
	 * Constructor for the campaign Node with associated JavaScript
	 * 
	 * @param name      String. The name of this node.
	 * @param heirarchy String. The hierarchy in the request associated with this
	 *                  node.
	 * @param operator  int. The operator to apply to this operation.
	 * @param value     Object. The constant to test the value of the hierarchy
	 *                  against.
	 * @param code      String. The Java code to execute if node evaluates true.
	 * @param shell     JJS. the encapsulated Nashhorn context to use for this
	 *                  operation.
	 * @throws Exception if the value object is not recognized.
	 */
	public Node(String name, String heirarchy, String operator, Object value, String code, JJS shell) throws Exception {
		this(name, heirarchy, operator, value);
		this.code = code;
		this.shell = shell;
	}

	/**
	 * Set the bidRequest values array from the hierarchy
	 */
	void setBRvalues() {
		if (hierarchy == null) // OR doesn't have a hierarchy
			return;

		String[] splitted = hierarchy.split("\\.");
		for (String s : splitted) {
			bidRequestValues.add(s);
		}
	}

	/**
	 * Used by FixedNodes
	 * 
	 * @param br
	 * @param creative
	 * @return
	 * @throws Exception
	 */
	public boolean test(BidRequest br, Creative creative, String adId, Impression imp, StringBuilder errorString,
			Probe probe, List<Deal> deals) throws Exception {

		return test(br, errorString);
	}

	public void setFalseCount(int n) {
		falseCount.set(n);
	}

	/**
	 * Test the bidrequest against this node
	 *
	 * @param br BidRequest. The bid request object to test.
	 * @return boolean. Returns true if br-value op value evaluates true. Else
	 *         false.
	 * @throws Exception if the request object and the values are not compatible.
	 */
	public boolean test(BidRequest br, StringBuilder errorString) throws Exception {
		boolean test = false;

		int oldOperator = operator;
		if (suboperator != -1) {
			operator = suboperator;
		}

		if (br.id.equals("123")) {
			testit = true;
		} else
			testit = false;

		if (operator == OR) {
			List<Node> nodes = (List) lval;
			boolean b = false;
			for (int i = 0; i < nodes.size(); i++) {
				Node node = nodes.get(i);
				node.notPresentOk = false;
				b |= node.test(br, errorString);
				if (b) {
					operator = oldOperator;
					return true;
				}
			}
			operator = oldOperator;
			falseCount.incrementAndGet();
			return false;

		}
		if (oldOperator == QUERY) {
			brValue = br.interrogate(hierarchy);
			JsonNode n = (JsonNode) brValue;
			String key = n.asText();
			Map map = (Map) value;
			brValue = map.get(key);
			test = testInternal(brValue);
		} else {
			try {
				if (hierarchy == null) {
					System.out.println("HERE");
				}
				brValue = br.interrogate(hierarchy);
			} catch (Exception e) {
				e.printStackTrace();
				throw new Exception("Bad hierarchy: " + hierarchy + ", " + e.toString());
			}
			if (brValue != null)
				test = testInternal(brValue);
			else {
				if (operator == NOT_EXISTS)
					test = true;
				if (notPresentOk)
					test = true;
			}
		}
		operator = oldOperator;
		if (!test) {
			if (errorString != null) {
				errorString.append(hierarchy + " resolved false");
			}
			falseCount.incrementAndGet();
		}
		return test;
	}

	/**
	 * Internal version of test() when recursion is required (NOT_* form)
	 * 
	 * @param value Object. Converts the value of the bid request field (Jackson) to
	 *              the appropriate Java object.
	 * @return boolean. Returns true if the operation succeeded.
	 * @throws Exception if the value is not recognized or is not compatible with
	 *                   this.value.
	 */
	public boolean testInternal(Object value) throws Exception {

		if (value == null || value instanceof MissingNode == true) { // the
																		// object
																		// requested
																		// is
																		// not
																		// in
																		// the
																		// bid
																		// request.
			if (!(operator == EXISTS || operator == NOT_EXISTS)) {
				if (notPresentOk)
					return true;
				else
					return false;
			}
		}

		Number nvalue = null;
		String svalue = null;
		// Set qvalue = null;
		Set qval = null;
		List list = null;

		if (value instanceof String)
			svalue = (String) value;
		if (value instanceof IntNode) {
			IntNode n = (IntNode) value;
			nvalue = n.numberValue();
		} else if (value instanceof TextNode) {
			TextNode tn = (TextNode) value;
			svalue = tn.textValue();
		} else if (value instanceof ArrayNode) {
			list = traverse((ArrayNode) value);
			try {
				qvalue = new TreeSet(list);
			} catch (Exception error) {

			}
		} else if (value instanceof ObjectNode) {
			ObjectNode n = (ObjectNode) value;
			mvalue = iterateObject(n);
		} else if (value instanceof Double) {
			DoubleNode n = new DoubleNode((Double) value); // (Node) value;
			nvalue = n.numberValue();
		} else if (value instanceof DoubleNode) {
			DoubleNode n = (DoubleNode) value;
			n.asDouble();
			DoubleNode nn = new DoubleNode(n.asDouble()); // (Node) value;
			nvalue = nn.numberValue();
		} else if (value instanceof Integer) {
			IntNode n = new IntNode((Integer) value); // (Node) value;
			nvalue = n.numberValue();
		} else if (value instanceof Collection) {
			qvalue = new TreeSet();
			qvalue.addAll((Collection) value);
		}

		switch (operator) {

		case QUERY:
			return true;

		case EQUALS:
			return processEquals(ival, nvalue, sval, svalue, qval, qvalue);
		case NOT_EQUALS:
			return !processEquals(ival, nvalue, sval, svalue, qval, qvalue);

		case STRINGIN:
			if (lval != null) {
				boolean member = false;
				for (int i = 0; i < lval.size(); i++) {
					Object test = lval.get(i);
					if (test instanceof String) {
						String testS = (String) test;
						member |= processStringin(ival, nvalue, testS, svalue, qval, qvalue);
					}
				}
				return member;
			}
			return processStringin(ival, nvalue, sval, svalue, qval, qvalue);

		case NOT_STRINGIN:
			if (lval != null) {
				boolean member = false;
				for (int i = 0; i < lval.size(); i++) {
					Object test = lval.get(i);
					if (test instanceof String) {
						String testS = (String) test;
						member |= processStringin(ival, nvalue, testS, svalue, qval, qvalue);
					}
				}
				return !member;
			}
			return !processStringin(ival, nvalue, sval, svalue, qval, qvalue);

		case REGEX:
		case NOT_REGEX:
			boolean member = true;
			if (svalue != null) {
				member = processRegex(ival, nvalue, sval, svalue, qval, qvalue);
			}
			if (operator == NOT_REGEX)
				return !member;
			else
				return member;

		case IDL:
		case NOT_IDL:
			boolean maybe = processIdl(list);
			if (operator == IDL)
				return maybe;
			return !maybe;
			
		case MEMBER:
		case NOT_MEMBER:

			if (sval != null && (sval.startsWith("@") || sval.startsWith("$"))) {
				boolean t = false;
				if (svalue != null && svalue.length() == 0) {
					t = false; // Technically "" is a member of any set, but ok, don't let it resolve true.
				} else {
					Object x = LookingGlass.get(sval);
					if (x == null) {
						Long evalue = errors.get(sval);
						if (evalue == null || (System.currentTimeMillis() - evalue > 60000)) {
							logger.error("Failed to retrieve symbol: {}", sval);
							errors.put(sval, System.currentTimeMillis());
						}
					}
					if (x instanceof NavMap) {
						NavMap nm = (NavMap) x;
						t = nm.contains(svalue);
					} else if (x instanceof Bloom) {
						Bloom b = (Bloom) x;
						t = b.mightContain(svalue);

					} else if (x instanceof SimpleSet) {
						SimpleSet set = (SimpleSet) x;
						t = set.getSet().contains(svalue);
					}
					
					else {
						// System.out.println("Error: ============> " + this.name + " DONT KNOW WHAT
						// THIS IS: " + x);
						t = false;
					}
				}

				if (operator == NOT_MEMBER)
					return !t;
				else
					return t;
			}

			if (qvalue == null) {
				if (lval != null)
					qvalue = new HashSet(lval);
				else {
					qvalue = new HashSet();
					if (svalue == null)
						qvalue.addAll((Collection) value);
					else
						qvalue.add(svalue);
				}
			}
			if (nvalue == null && svalue == null) {
				if (this.value instanceof String)
					svalue = (String) this.value;
				else {
					try {
						nvalue = (Integer) this.value;
					} catch (Exception error) {
						return false;
						// System.out.println("QVALUE: " + qvalue);
						// System.out.println("THIS VALUE: " + this.value);
						// System.out.println("VALUE: " + value);
					}
				}
			}

			boolean test = false;
			test = processMember(nvalue, svalue, qvalue);
			if (operator == MEMBER)
				return test;
			else
				return !test;

		case INTERSECTS:
		case NOT_INTERSECTS:

			if (qvalue == null) {
				if (lval != null)
					qvalue = new TreeSet(lval);
			}
			if (qval == null) {
				if (svalue != null) {
					qval = new TreeSet();
					qval.add(svalue);
				} else {
					if (nvalue != null) {
						qval = new TreeSet();
						qval.add(nvalue);
					} else if (lval != null) {
						qval = new TreeSet(lval);
					}
				}
			} else {
				if (svalue != null) {
					qval.add(svalue);
				} else {
					if (nvalue != null) {
						qval.add(nvalue.intValue());
					}
				}
			}

			if (qval == null)
				qval = new TreeSet(lval);

			boolean xxx = processIntersects(qval, qvalue);
			if (operator == INTERSECTS)
				return xxx;
			return !xxx;

		case INRANGE:
			return computeInRange(mvalue);
		case NOT_INRANGE:
			return !computeInRange(mvalue);

		case DOMAIN:
			return computeInDomain(nvalue, lval);
		case NOT_DOMAIN:
			return !computeInDomain(nvalue, lval);

		case LESS_THAN:
		case LESS_THAN_EQUALS:
		case GREATER_THAN:
		case GREATER_THAN_EQUALS:
			return processLTE(operator, ival, nvalue, sval, svalue, qval, qvalue);

		case EXISTS:
		case NOT_EXISTS:
			boolean rc = false;
			if (value == null || value instanceof MissingNode)
				rc = false;
			else
				rc = true;
			if (operator == EXISTS)
				return rc;
			return !rc;

		default:
			return false;
		// throw new Exception("Undefined operation attempted");
		}
	}
	
	Boolean processIdl(List values) {
		Object x = (Object)LookingGlass.get((String)value);
		if (x == null) {
			Long evalue = errors.get(sval);
			if (evalue == null || (System.currentTimeMillis() - evalue > 60000)) {
				logger.error("Failed to retrieve symbol: {}", sval);
				errors.put(sval, System.currentTimeMillis());
			}
			return null;
		}
		Bloom filter = (Bloom)x;
		for (int i=0;i<values.size();i++) {
			JsonNode oj = (JsonNode)values.get(i);
			String src = oj.get("source").asText("");
			if (src.equals("liveramp.com")) {
				ArrayNode nodes = (ArrayNode)oj.get("uids");
				for (int j=0;j<nodes.size();j++) {
					String id = nodes.get(j).get("id").asText("");
					if (filter.isMember(id))
						return true;
				}
			}
		}
		return false;
	}

	/**
	 * Processes the relational operators.
	 * 
	 * @param operator int. Less than, less than equal, etc...
	 * @param ival     Number. The constant's value if a number.
	 * @param nvalue   Number. The bid request's value if a number,
	 * @param sval     String. the constant's value if a String.
	 * @param svalue   String. The bid request value if a String.
	 * @param qval     Set. The constant's value if it is a Set.
	 * @param qvalue   Set. The bid requests value if it is a Set.
	 * @return boolean. Returns the value of the operation (true or false).
	 */
	public boolean processLTE(int operator, Number ival, Number nvalue, String sval, String svalue, Set qval,
			Set qvalue) {
		if (ival == null || nvalue == null)
			return false;
		switch (operator) {
		case LESS_THAN:
			// return ival.doubleValue() < nvalue.doubleValue();
			return nvalue.doubleValue() < ival.doubleValue();
		case LESS_THAN_EQUALS:
			// return ival.doubleValue() <= nvalue.doubleValue();
			return nvalue.doubleValue() <= ival.doubleValue();
		case GREATER_THAN:
			// return ival.doubleValue() > nvalue.doubleValue();
			return nvalue.doubleValue() > ival.doubleValue();
		case GREATER_THAN_EQUALS:
			// return ival.doubleValue() >= nvalue.doubleValue();
			return nvalue.doubleValue() >= ival.doubleValue();
		}
		return false;
	}

	boolean jedisIsMember(String key, String member) {
		boolean t = false;
		if (Configuration.getInstance().jedisPool == null)
			return false;

		try {

			if (member.equals("842AAB10FBA04247B3A9CE00C9172350")) {
				System.out.println("$$$$$$$$$$$$$$$$$$$ KEYTEST on " + key);
			}
			// Jedis jedis =
			// Configuration.getInstance().jedisPool.getResource();
			Jedis jedis = Configuration.getInstance().jedisPool.borrowObject();
			t = jedis.sismember(key, member);

			if (member.equals("842AAB10FBA04247B3A9CE00C9172350")) {
				System.out.println("$$$$$$$$$$$$$$$$$$$ TEST RETURNS: " + t);
			}
			// Configuration.getInstance().jedisPool.returnResource(jedis);
			Configuration.getInstance().jedisPool.returnObject(jedis);
			// t = Configuration.getInstance().jedisPool.sismember(key, member);
		} catch (Exception error) {
			error.printStackTrace();
		}
		return t;
	}

	/**
	 * Determine if the value of this node object equals that of what is found in
	 * the bid request object.
	 * 
	 * @param ival   Number. The constant's value if a number.
	 * @param nvalue Number. The bid request's value if a number,
	 * @param sval   String. The constant's value if a String.
	 * @param svalue String. The bid request value if a String.
	 * @param qval   Set. The constant's value if it is a Set.
	 * @param qvalue Set. The bid requests value if it is a Set.
	 * @return boolean. Returns true if operation is true.
	 */
	public boolean processEquals(Number ival, Number nvalue, String sval, String svalue, Set qval, Set qvalue) {
		if (ival != null) {
			if (ival == null || nvalue == null)
				return false;
			double a = ival.doubleValue();
			double b = nvalue.doubleValue();
			return a == b;
		} else if (sval != null) {
			return sval.equals(svalue);
		} else if (qval != null) {
			if (qval.size() != qvalue.size())
				return false;
			return qval.containsAll(qvalue);
		}
		return false;
	}

	/**
	 * Is sval a substring of svalue?
	 * 
	 * @param ival
	 * @param nvalue
	 * @param sval
	 * @param svalue
	 * @param qval
	 * @param qvalue
	 * @return
	 */
	public boolean processStringin(Number ival, Number nvalue, String sval, String svalue, Set qval, Set qvalue) {
		if (sval != null) {
			return svalue.indexOf(sval) > -1;
		}
		return false;
	}

	public boolean processRegex(Number ival, Number nvalue, String sval, String svalue, Set qval, Set qvalue) {
		if (sval != null) {
			if (pattern == null) {
				pattern = Pattern.compile(sval);
			}
			Matcher matcher = pattern.matcher(svalue);
			return matcher.matches();
		}
		return true;
	}

	/**
	 * Compute range in meters from qval (set, lat, lon, meters) against a set of
	 * 
	 * @param pos    Map. A map of the geo object in the bid request; containing
	 *               keys "lat","lon","type".
	 * @param qvalue List. A list of maps defining "lat", "lon","range" for testing
	 *               against multiple regions. This is the constant value.
	 * @return boolean. Returns true if any of the qvalue regions is in range of
	 *         pos.
	 */
	public boolean computeInRange(Map<String, Double> pos) {
		double plat = pos.get("lat");
		double plon = pos.get("lon");
		for (int i = 0; i < points.size(); i++) {
			Point p = points.get(i);
			double dist = getRange(p.lat, p.lon, plat, plon);
			if (dist < p.range)
				return true;
		}
		return false;
	}

	/**
	 * Compute distance in meters between xlat,xlon and ylat,ylon
	 * 
	 * @param xlat - First point's latitude
	 * @param xlon - First point's longitude
	 * @param ylat - Second point's latitude
	 * @param ylon - Second point's longitude
	 * @return double. Distance in meters between these 2 points.
	 */
	public static double getRange(Number xlat, Number xlon, Number ylat, Number ylon) {
		double lat1 = xlat.doubleValue();
		double long1 = xlon.doubleValue();

		double lat2 = ylat.doubleValue();
		double long2 = ylon.doubleValue();

		double dlat1 = lat1 * (Math.PI / 180);

		double dlong1 = long1 * (Math.PI / 180);
		double dlat2 = lat2 * (Math.PI / 180);
		double dlong2 = long2 * (Math.PI / 180);

		double dLong = dlong1 - dlong2;
		double dLat = dlat1 - dlat2;

		double aHarv = Math.pow(Math.sin(dLat / 2.0), 2.0)
				+ Math.cos(dlat1) * Math.cos(dlat2) * Math.pow(Math.sin(dLong / 2), 2);
		double cHarv = 2 * Math.atan2(Math.sqrt(aHarv), Math.sqrt(1.0 - aHarv));
		// earth's radius from wikipedia varies between 6,356.750 km � 6,378.135
		// km (�3,949.901 � 3,963.189 miles)
		// The IUGG value for the equatorial radius of the Earth is 6378.137 km
		// (3963.19 mile)
		double earth = 6378.137 * 1000; // meters

		return earth * cHarv;
	}

	/**
	 * Determine of the value of this node is in the domain of the other node.
	 * 
	 * @param ival   Number. The value to be tested.
	 * @param qvalue List. The low and high values to test
	 * @return boolean. Returns true of value is in the domain of qvalue, else
	 *         false;
	 * @throws Exception if the values being compared are not compatible or not a
	 *                   recognized type.
	 */
	public boolean computeInDomain(Number ival, List qvalue) throws Exception {
		if (qvalue.size() != 2)
			throw new Exception("Domain computation requires a low and high range (2 value)");
		double value = ival.doubleValue();

		double low = (Double) qvalue.get(0);
		double high = (Double) qvalue.get(1);
		if (value >= low && value <= high)
			return true;
		return false;
	}

	/**
	 * Process membership of scalar value in the list provided in the bid request.
	 * 
	 * @param ival   Number. The constant's value if a number.
	 * @param sval   String. The constan't value if a string.
	 * @param qvalue Set. The bid request values.
	 * @return boolean. Returns true of ival/sval in qvalue.
	 */
	boolean processMember(Number ival, String sval, Set qvalue) {
		try {
			boolean ok = false;
			if (ival != null) {
				Object x = qvalue.iterator().next();
				if (ival instanceof Double && x instanceof Integer) {
					ival = ival.intValue();
				}
				ok = qvalue.contains(ival);
			}
			if (sval != null) {
				if (sval.length() == 0)
					ok = false;
				else
					ok = qvalue.contains(sval);
			}
			return ok;
		} catch (Exception e) {

		}
		return false;
	}

	/**
	 * Process the intersection of the node value and that of the value in the bid
	 * request.
	 * 
	 * @param qval   Set. The set of things from the constant object.
	 * @param qvalue Set. Te set of things from the bid request.
	 * @return boolean. Returns true if there is an intersection.
	 */
	boolean processIntersects(Set qval, Set qvalue) {
		qval.retainAll(qvalue);
		return !(qval.size() == 0);
	}

	/**
	 * Iterate over a Jackson object and create a Java Map.
	 * 
	 * @param node ObjectNode. The Jackson node to set up as a Map.
	 * @return Map. Returns the Map implementation of the Jackson node.
	 */
	Map iterateObject(ObjectNode node) {
		Map m = new HashMap();
		Iterator it = node.iterator();
		it = node.fieldNames();
		while (it.hasNext()) {
			String key = (String) it.next();
			Object s = node.get(key);
			if (s instanceof TextNode) {
				TextNode t = (TextNode) s;
				m.put(key, t.textValue());
			} else if (s instanceof DoubleNode) {
				DoubleNode t = (DoubleNode) s;
				m.put(key, t.numberValue());
			} else if (s instanceof IntNode) {
				IntNode t = (IntNode) s;
				m.put(key, t.numberValue());
			} else
				m.put(key, s); // indeterminate, need to traverse
		}
		return m;
	}

	/**
	 * Traverse an ArrayNode and convert to ArrayList
	 * 
	 * @param n ArrayNode. A Jackson ArrayNode.
	 * @return List. The list that corresponds to the Jackson ArrayNode.
	 */
	List traverse(ArrayNode n) {
		List list = new ArrayList();

		for (int i = 0; i < n.size(); i++) {
			Object obj = n.get(i);
			if (obj instanceof IntNode) {
				IntNode d = (IntNode) obj;
				list.add(d.numberValue());
			} else if (obj instanceof DoubleNode) {
				DoubleNode d = (DoubleNode) obj;
				list.add(d.numberValue());
			} else if (obj instanceof ArrayNode) {
				ArrayNode nodes = (ArrayNode) obj;
				for (int k = 0; i < nodes.size(); i++) {
					list.add(nodes.get(k));
				}
			} else if (obj instanceof TextNode) {
				TextNode t = (TextNode) obj;
				list.add(t.textValue());
			} else {
				list.add(obj);
			}
		}

		return list;
	}

	/**
	 * Returns the value of the interrogate of the bid request.
	 * 
	 * @return Object. The value of the bid request derived from the query of the
	 *         hierarchy.
	 */
	@JsonIgnore
	public Object getBRvalue() {
		return brValue;
	}

	/**
	 * Return the integer value, if it is a number
	 * 
	 * @return Integer. The integer value, or null if not a number
	 */
	public Integer intValue() {
		if (ival == null)
			return null;
		return ival.intValue();
	}

	/**
	 * Return the double value, if it is a number
	 * 
	 * @return Double. The doublr value, or null if not a number
	 */
	public Double doubleValue() {
		if (ival == null)
			return null;
		return ival.doubleValue();
	}

	@JsonIgnore
	public String getLucene() {
		String stuff = "";

		if (value.toString().startsWith("@") == true)
			return null;

		String hr = this.hierarchy.replace("imp.0.", "imp.");
		hr = hr.replaceAll("exchange", "ext.exchange");

		if (this.notPresentOk == true) {
			stuff = "((-_exists_: " + hr + ") OR ";
		}

		String strValue = value.toString();
		strValue = strValue.replaceAll("/", "\\\\/");

		switch (operator) {

		case QUERY:
			return null;

		case EQUALS:
			stuff += hr + ": " + strValue;
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case NOT_EQUALS:
			stuff += "-" + hr + ": " + strValue;
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case STRINGIN:
			stuff += hr + ": \"" + strValue + "\"";
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case NOT_STRINGIN:
			stuff += "-" + hr + ": \"" + strValue + "\"";
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case NOT_INTERSECTS:
			if (value instanceof List) {
				String str = "(";
				List list = (List) value;
				for (int i = 0; i < list.size(); i++) {
					str += "-" + hr + ": *" + list.get(i) + "*";

					str = str.replaceAll("/", "\\\\/");

					if (i + 1 < list.size()) {
						str += " OR ";
					}
				}
				str += ")";
				stuff += str;
				if (notPresentOk)
					stuff += ")";
				return stuff;
			}
			stuff += "-" + hr + ": " + strValue;
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case INTERSECTS:
			if (value instanceof List) {
				String str = "(";
				List list = (List) value;
				for (int i = 0; i < list.size(); i++) {
					str += hr + ": *" + list.get(i) + "*";

					str = str.replaceAll("/", "\\\\/");

					if (i + 1 < list.size()) {
						str += " OR ";
					}
				}
				str += ")";
				stuff += str;
				if (notPresentOk)
					stuff += ")";
				return stuff;
			}
			stuff += hr + ": *" + strValue + "*";
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case MEMBER:
			if (value instanceof List) {
				String str = "(";
				List list = (List) value;
				for (int i = 0; i < list.size(); i++) {
					str += hr + ": *" + list.get(i) + "*";

					str = str.replaceAll("/", "\\\\/");

					if (i + 1 < list.size()) {
						str += " OR ";
					}
				}
				str += ")";
				stuff += str;
				if (notPresentOk)
					stuff += ")";
				return stuff;
			}
			stuff += hr + ": *" + strValue + "*";
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case NOT_MEMBER:
			if (value instanceof List) {
				String str = "(";
				List list = (List) value;
				for (int i = 0; i < list.size(); i++) {
					str += "-" + hr + ": *" + list.get(i) + "*";

					str = str.replaceAll("/", "\\\\/");

					if (i + 1 < list.size()) {
						str += " OR ";
					}
				}
				str += ")";
				stuff += str;
				if (notPresentOk)
					stuff += ")";
				return stuff;
			}

			stuff += "-" + hr + ": *" + strValue + "*";
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case INRANGE:
			List o = (List) value;
			stuff += hr + ": [" + o.get(0) + " TO " + o.get(1) + "]";
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case NOT_INRANGE:
			List list = (List) value;
			stuff += "-" + hr + ": [" + list.get(0) + " TO " + list.get(1) + "]";
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case DOMAIN:
			list = (List) value;
			stuff += "(" + hr + "< " + list.get(1) + " AND " + hr + "> " + list.get(0) + ")";
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case NOT_DOMAIN:
			list = (List) value;
			stuff += "(" + hr + "> " + list.get(1) + " OR " + hr + "< " + list.get(0) + ")";
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case LESS_THAN:
			stuff += hr + "< " + strValue;
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case LESS_THAN_EQUALS:
			stuff += hr + "<= " + strValue;
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case GREATER_THAN:
			stuff = hr + "< " + strValue;
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case GREATER_THAN_EQUALS:
			stuff += hr + ">= " + strValue;
			if (notPresentOk)
				stuff += ")";
			return stuff;

		case EXISTS:
			return "_exists_: " + hr;

		case NOT_EXISTS:
			return "_missing_: " + hr;
		}

		return "";

	}
}

class Point {
	public double lat;
	public double lon;
	public double range;

	public Point(double lat, double lon, double range) {
		this.lat = lat;
		this.lon = lon;
		this.range = range;
	}
	
	public Point() {
		
	}
};
