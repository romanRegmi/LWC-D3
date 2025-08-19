// HierarchyController.cls
public with sharing class HierarchyController {
    private static Integer maxLevel;

    // API name of the field you want to display as the label in the node for each child object record
    private static final Map<String, String> NAME_FIELD_MAP = new Map<String, String>{
        'Account' => 'Name',
        'Contact' => 'Name',
        'Opportunity' => 'Name',
        'Case' => 'Subject',
        'CaseComment' => 'ParentId'
    };

    @AuraEnabled
    public static HierarchyNode getHierarchyData(String recordId, String currObj, Integer maxLevels) {
        try {
            if (String.isBlank(recordId) || String.isBlank(currObj) || maxLevels <= 0) {
                throw new AuraHandledException('Invalid parameters provided');
            }
            
            maxLevel = maxLevels;
                        
            // Get the root record
            SObject rootRecord = getRootRecord(recordId, currObj);
            if (rootRecord == null) {
                throw new AuraHandledException('Record not found');
            }

            // Build hierarchy starting from root with object grouping enabled
            HierarchyNode rootNode = buildHierarchyNode(rootRecord, currObj, 0, new Set<String>(), true);            
            return rootNode;
            
        } catch (Exception e) {
            throw new AuraHandledException('Error retrieving hierarchy data: ' + e.getMessage());
        }
    }
    
    private static SObject getRootRecord(String recordId, String currObj) {
        return Database.query('SELECT Id, Name FROM ' + String.escapeSingleQuotes(currObj) + ' WHERE Id = :recordId LIMIT 1');
    }
    
    private static HierarchyNode buildHierarchyNode(SObject record, String currObj, Integer currentLevel, Set<String> processedRecords, Boolean isRoot) {
        // Prevent infinite loops
        try {
            if (processedRecords.contains(record.Id)) {
                return null;
            }
            processedRecords.add(record.Id);
            
            HierarchyNode node = new HierarchyNode();
            node.id = record.Id;
            node.name = NAME_FIELD_MAP.get(currObj) != null ? String.valueOf(record.get(NAME_FIELD_MAP.get(currObj))) : null;
            node.currObj = currObj;
            node.children = new List<HierarchyNode>();
            
            // If we haven't reached max levels, get children
            if (currentLevel < maxLevel) {
                // Always group children by object type (not just for root)
                List<HierarchyNode> groupedChildren = getGroupedChildRecords(record.Id, currObj, currentLevel + 1, processedRecords);
                node.children.addAll(groupedChildren);
            }
            return node;
        } catch (Exception e) {
            System.debug('Error in buildHierarchyNode: ' + e.getMessage());
            return null;
        }
    }
    
    // Group children by object type (used at all levels)
    private static List<HierarchyNode> getGroupedChildRecords(String parentId, String parentObjectType, Integer currentLevel, Set<String> processedRecords) {
        List<HierarchyNode> groupedNodes = new List<HierarchyNode>();
        
        // Get all child records first
        Map<String, List<SObject>> childRecordsByObjectType = getChildRecordsByObjectType(parentId, parentObjectType);
        
        // Create object group nodes for each object type that has records
        for (String objectType : childRecordsByObjectType.keySet()) {
            List<SObject> objectRecords = childRecordsByObjectType.get(objectType);
            
            if (!objectRecords.isEmpty()) {
                // Create object group node
                HierarchyNode objectGroupNode = new HierarchyNode();
                objectGroupNode.id = parentId + '_' + objectType;
                objectGroupNode.name = objectType;
                objectGroupNode.currObj = objectType;
                objectGroupNode.isObjectGroup = true;
                objectGroupNode.children = new List<HierarchyNode>();
                
                // Add individual records as children of the object group
                for (SObject childRecord : objectRecords) {
                    if (!processedRecords.contains(childRecord.Id)) {
                        // Recursively build child nodes (they will also group their children)
                        HierarchyNode childNode = buildHierarchyNode(
                            childRecord, objectType, currentLevel, processedRecords, false
                        );
                        if (childNode != null) {
                            objectGroupNode.children.add(childNode);
                        }
                    }
                }
                
                // Only add object group if it has children
                if (!objectGroupNode.children.isEmpty()) {
                    groupedNodes.add(objectGroupNode);
                }
            }
        }
        
        return groupedNodes;
    }
    
    // New method to get child records grouped by object type
    private static Map<String, List<SObject>> getChildRecordsByObjectType(String parentId, String parentObjectType) {
        Map<String, List<SObject>> childRecordsByType = new Map<String, List<SObject>>();
        
        // Define relationship mappings
        Map<String, List<ChildRelationship>> relationshipMap = getRelationshipMappings();
        
        if (relationshipMap.containsKey(parentObjectType)) {
            for (ChildRelationship relationship : relationshipMap.get(parentObjectType)) {
                try {
                    // Build dynamic query for child records
                    List<String> queryFields = new List<String>{'Id'};
                    if (!String.isBlank(NAME_FIELD_MAP.get(relationship.childObjectName))) {
                        queryFields.add(NAME_FIELD_MAP.get(relationship.childObjectName));
                    }
                    
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
                    System.debug('Error querying child records for relationship ' + relationship.childObjectName + ': ' + e.getMessage());
                }
            }
        }
        
        return childRecordsByType;
    }
    
    // Modified original method to support non-grouped children
    private static List<HierarchyNode> getChildRecords(String parentId, String parentObjectType, Integer currentLevel, Set<String> processedRecords, Boolean isRoot) {
        List<HierarchyNode> childNodes = new List<HierarchyNode>();
        
        // Define relationship mappings
        Map<String, List<ChildRelationship>> relationshipMap = getRelationshipMappings();
        
        if (relationshipMap.containsKey(parentObjectType)) {
            for (ChildRelationship relationship : relationshipMap.get(parentObjectType)) {
                List<HierarchyNode> relationshipChildren = getChildrenForRelationship(
                    parentId, relationship, currentLevel, processedRecords
                );
                childNodes.addAll(relationshipChildren);
            }
        }
        
        return childNodes;
    }
    
    private static List<HierarchyNode> getChildrenForRelationship(String parentId, ChildRelationship relationship, Integer currentLevel, Set<String> processedRecords) {
        List<HierarchyNode> children = new List<HierarchyNode>();
        
        try {
            // Build dynamic query for child records
            List<String> queryFields = new List<String>{'Id'};
            if (!String.isBlank(NAME_FIELD_MAP.get(relationship.childObjectName))) {
                queryFields.add(NAME_FIELD_MAP.get(relationship.childObjectName));
            }
            
            String query = 'SELECT ' + String.join(queryFields, ', ') + 
                          ' FROM ' + String.escapeSingleQuotes(relationship.childObjectName) + 
                          ' WHERE ' + String.escapeSingleQuotes(relationship.relationshipField) + ' = :parentId' +
                          ' ORDER BY CreatedDate DESC';
            
            List<SObject> childRecords = Database.query(query);
            
            for (SObject childRecord : childRecords) {
                if (!processedRecords.contains(childRecord.Id)) {
                    HierarchyNode childNode = buildHierarchyNode(
                        childRecord, relationship.childObjectName, currentLevel, processedRecords, false
                    );
                    if (childNode != null) {
                        children.add(childNode);
                    }
                }
            }
            
        } catch (Exception e) {
            System.debug('Error querying child records for relationship ' + relationship.childObjectName + ': ' + e.getMessage());
        }
        
        return children;
    }
    
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
        
        // Add more relationships as needed for your org
        
        return relationshipMap;
    }
    
    // Enhanced wrapper class
    public class HierarchyNode {
        @AuraEnabled public String id { get; set; }
        @AuraEnabled public String name { get; set; }
        @AuraEnabled public String currObj { get; set; }
        @AuraEnabled public Boolean isObjectGroup { get; set; }
        @AuraEnabled public List<HierarchyNode> children { get; set; }
        
        public HierarchyNode() {
            this.children = new List<HierarchyNode>();
            this.isObjectGroup = false;
        }
    }
    
    private class ChildRelationship {
        public String childObjectName { get; set; }
        public String relationshipField { get; set; }
        
        public ChildRelationship(String childObjectName, String relationshipField) {
            this.childObjectName = childObjectName;
            this.relationshipField = relationshipField;
        }
    }
}