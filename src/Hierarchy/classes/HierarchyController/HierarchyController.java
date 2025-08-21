/* Build the hierarchy */
public with sharing class HierarchyController {
    // Maximum depth level for the hierarchy traversal
    private static Integer maxLevel;

    // Map defining the API name of the field to use as the display label for the record nodes.
    // Extend this map when adding support for new objects.
    private static final Map<String, String> NAME_FIELD_MAP = new Map<String, String>{
        'Account' => 'Name',
        'Contact' => 'Name',
        'Opportunity' => 'Name',
        'Case' => 'Subject',
        'CaseComment' => 'ParentId'
    };
        
    /* Method to fetch hierarchy data for a given record
     * @param recordId - The Id of the root record to start the hierarchy from
     * @param currObj - Root object API name
     * @param maxLevels - The maximum depth to traverse in the hierarchy
     * @return - A HierarchyNode representing the root of the hierarchy
    */ 
    @AuraEnabled
    public static HierarchyNode getHierarchyData(String recordId, String currObj, Integer maxLevels) {
        try {
            if (String.isBlank(recordId) || String.isBlank(currObj) || maxLevels <= 0) {
                throw new AuraHandledException('Invalid parameters provided');
            }
            
            maxLevel = maxLevels;
                        
            // Retrieve the root record using a dynamic query.
            SObject rootRecord = getRootRecord(recordId, currObj);
            if (rootRecord == null) {
                throw new AuraHandledException('Record not found');
            }

            // Recursively build the hierarchy tree, starting from the root, with object grouping enabled.
            HierarchyNode rootNode = buildHierarchyNode(rootRecord, currObj, 0, new Set<String>());
            return rootNode;
            
        } catch (Exception e) {
            // Handle any exceptions and rethrow as AuraHandledException for Lightning component error handling.
            throw new AuraHandledException('Error retrieving hierarchy data: ' + e.getMessage());
        }
    }
    
    /* Helper method to query and retrieve the root record
     * @param recordId - The Id of the root record
     * @param currObj - The object API name
     * @return - The sObject record 
    */
    private static SObject getRootRecord(String recordId, String currObj) {
        return Database.query('SELECT Id, ' + NAME_FIELD_MAP.get(currObj) + ' FROM ' + String.escapeSingleQuotes(currObj) + ' WHERE Id = :recordId LIMIT 1');
    }
    
    /* Recursive method to build a HierarchyNode for a given record.
     * @param record - The current sObject record
     * @param currObj - The current object API name
     * @param currentLevel - The current depth in the hierarchy.
     * @param processedRecords - Set of record IDs already processed to prevent infinite loops.
     * @return - A HierarchyNode or null if an error occurs or loop detected. 
    */
    private static HierarchyNode buildHierarchyNode(SObject record, String currObj, Integer currentLevel, Set<String> processedRecords) {
        // Prevent infinite loops by checking if the record has already been processed.
        try {
            if (processedRecords.contains(record.Id)) {
                return null;
            }
            processedRecords.add(record.Id);
            
            // Initialize the node with basic properties.
            HierarchyNode node = new HierarchyNode();
            node.id = record.Id;
            node.label = NAME_FIELD_MAP.get(currObj) != null ? String.valueOf(record.get(NAME_FIELD_MAP.get(currObj))) : null;
            node.currObj = currObj;
            node.children = new List<HierarchyNode>();
            
            // Recurse to add children if the maximum level hasn't been reached.
            if (currentLevel < maxLevel) {
                // Fetch and group child records by object type.
                List<HierarchyNode> groupedChildren = getGroupedChildRecords(record.Id, currObj, currentLevel + 1, processedRecords);
                node.children.addAll(groupedChildren);
            }
            return node;
        } catch (Exception e) {
            // Log errors and return null to skip problematic nodes.
            System.debug('Error in buildHierarchyNode: ' + e.getMessage());
            return null;
        }
    }
    
    /* Method to retrieve and group child records by their object type.
     * @param parentId - The Id of the parent record.
     * @param parentObjectType - The API name of the parent object.
     * @param currentLevel - The next depth level for children.
     * @param processedRecords - Set for loop prevention.
     * @return - List of HierarchyNode representing grouped children. 
    */
    private static List<HierarchyNode> getGroupedChildRecords(String parentId, String parentObjectType, Integer currentLevel, Set<String> processedRecords) {
        List<HierarchyNode> groupedNodes = new List<HierarchyNode>();
        
        // Fetch all child records, grouped by object type.
        Map<String, List<SObject>> childRecordsByObjectType = getChildRecordsByObjectType(parentId, parentObjectType);
        
        // For each object type with children, create a group node.
        for (String objectType : childRecordsByObjectType.keySet()) {
            List<SObject> objectRecords = childRecordsByObjectType.get(objectType);
            
            if (!objectRecords.isEmpty()) {
                // Create a group node for the object type.
                HierarchyNode objectGroupNode = new HierarchyNode();
                objectGroupNode.id = parentId + '_' + objectType;
                objectGroupNode.label = objectType;
                objectGroupNode.currObj = objectType;
                objectGroupNode.isObjectGroup = true;
                objectGroupNode.children = new List<HierarchyNode>();
                
                // Add individual child nodes under the group.
                for (SObject childRecord : objectRecords) {
                    if (!processedRecords.contains(childRecord.Id)) {
                        // Recursively build the child hierarchy.
                        HierarchyNode childNode = buildHierarchyNode(
                            childRecord, objectType, currentLevel, processedRecords
                        );
                        if (childNode != null) {
                            objectGroupNode.children.add(childNode);
                        }
                    }
                }
                
                // Add the group node only if it has children.
                if (!objectGroupNode.children.isEmpty()) {
                    groupedNodes.add(objectGroupNode);
                }
            }
        }
        
        return groupedNodes;
    }
    
    /* Method to query child records for a parent, grouped by child object type.
     * @param parentId - The parent record ID.
     * @param parentObjectType - The parent object API name.
     * @return - Map of child object type to list of child SObjects. 
    */
    private static Map<String, List<SObject>> getChildRecordsByObjectType(String parentId, String parentObjectType) {
        Map<String, List<SObject>> childRecordsByType = new Map<String, List<SObject>>();
        
        // Get predefined child relationships for the parent object.
        Map<String, List<ChildRelationship>> relationshipMap = getRelationshipMappings();
        
        if (relationshipMap.containsKey(parentObjectType)) {
            for (ChildRelationship relationship : relationshipMap.get(parentObjectType)) {
                try {
                    // Prepare fields for the dynamic query, including ID and name field if defined.
                    List<String> queryFields = new List<String>{'Id'};
                    if (!String.isBlank(NAME_FIELD_MAP.get(relationship.childObjectName))) {
                        queryFields.add(NAME_FIELD_MAP.get(relationship.childObjectName));
                    }
                    
                    // Build and execute the dynamic SOQL query for children.
                    String query = 'SELECT ' + String.join(queryFields, ', ') + 
                                  ' FROM ' + String.escapeSingleQuotes(relationship.childObjectName) + 
                                  ' WHERE ' + String.escapeSingleQuotes(relationship.relationshipField) + ' = :parentId' +
                                  ' ORDER BY CreatedDate DESC';
                    
                    List<SObject> childRecords = Database.query(query);
                    
                    if (!childRecords.isEmpty()) {
                        if (!childRecordsByType.containsKey(relationship.childObjectName)) {
                            childRecordsByType.put(relationship.childObjectName, new List<SObject>());
                        }
                        childRecordsByType.get(relationship.childObjectName).addAll(childRecords);
                    }
                    
                } catch (Exception e) {
                    // Log query errors but continue with other relationships.
                    System.debug('Error querying child records for relationship ' + relationship.childObjectName + ': ' + e.getMessage());
                }
            }
        }
        
        return childRecordsByType;
    }
    
    /*
     * Method to define parent-child relationship mappings
     * @return - Map of Parent Object API Name to list of ChildRelationship
    */
    private static Map<String, List<ChildRelationship>> getRelationshipMappings() {
        // Customize this map based on your org's object relationships
        Map<String, List<ChildRelationship>> relationshipMap = new Map<String, List<ChildRelationship>>();
        
        // Account relationships
        relationshipMap.put('Account', new List<ChildRelationship>{
            new ChildRelationship('Contact', 'AccountId'),
            new ChildRelationship('Opportunity', 'AccountId'),
            new ChildRelationship('Case', 'AccountId'),
            new ChildRelationship('Account', 'ParentId') // Self relationship
        });
        
        // Contact relationships
        relationshipMap.put('Contact', new List<ChildRelationship>{
            new ChildRelationship('Case', 'ContactId')
        });
        
        // Opportunity relationships
        relationshipMap.put('Opportunity', new List<ChildRelationship>{
            new ChildRelationship('OpportunityLineItem', 'OpportunityId')
        });
        
        // Case relationships
        relationshipMap.put('Case', new List<ChildRelationship>{
            new ChildRelationship('Case', 'ParentId'), // Child Cases
            new ChildRelationship('CaseComment', 'ParentId')
        });
        
        // Add more relationships as needed
        
        return relationshipMap;
    }
    

    /**
     * Wrapper class for Hierarchy Nodes
     * @property id - Node identifier
     * @property label - Node label
     * @property currObj - API Name of the current object 
     * @property isObjectGroup - Flag to determine if the node is a record node or an object node
     * @property children - List of child nodes.
    */
    public class HierarchyNode {
        @AuraEnabled public String id { get; set; }
        @AuraEnabled public String label { get; set; }
        @AuraEnabled public String currObj { get; set; }
        @AuraEnabled public Boolean isObjectGroup { get; set; }
        @AuraEnabled public List<HierarchyNode> children { get; set; }
        
        public HierarchyNode() {
            this.children = new List<HierarchyNode>();
            this.isObjectGroup = false;
        }
    }
    
    /**
     * Inner class to represent child relationships.
     * @property childObjectName - API name of the child object.
     * @property relationshipField - Lookup field on child pointing to parent.
    */
    private class ChildRelationship {
        public String childObjectName { get; set; }
        public String relationshipField { get; set; }
        
        public ChildRelationship(String childObjectName, String relationshipField) {
            this.childObjectName = childObjectName;
            this.relationshipField = relationshipField;
        }
    }
}