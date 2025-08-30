<div align="center">
	<img
	width="50">
	<h1>SHOW HIERARCHY</h1>
</div>

<h3 align="center">
	An Interactive Lightning Web Component for the Salesforce Platform<br><br>
</h3>

The D3.js tree hierarchy chart in a Salesforce LWC displays a visual representation of a parent-child relationship. The component is added to an object's record detail page. Each child record of the record the component is on is displayed as a circular, clickable node. When a node is clicked, the user is redirected to the record that the clicked node represents.

<p align="center">
  <img alt="Hierarchy" src="images/appDemo.gif">
</p>


<div align="center">
	<img
	width="50">
	<h2>Prerequisites</h2>
</div>

1. The LWC must be added to the record detail page of supported objects (e.g., Account, Contact, Opportunity, Case).

2. The D3.js (d3.v7.min.js) library must be added as Static Resource in the Salesforce Platform as **d3js.zip** file for rendering the tree chart.

<div align="center">
	<img
	width="50">
	<h2>Implementation</h2>
</div>

Modifications in the Hierarchy Controller Apex class must be done for customizations. 

1. Update NAME_FIELD_MAP

    The NAME_FIELD_MAP static map defines the field used as the display label for each object's nodes in the D3.js chart.

    ```apex
    private static final Map<String, String> NAME_FIELD_MAP = new Map<String, String>{
        'Account' => 'Name',
        'Contact' => 'Name',
        'Opportunity' => 'Name',
        'Case' => 'Subject',
        'CaseComment' => 'ParentId',
        'CustomObject__c' => 'CustomNameField__c' // Add custom object
    };
    ```
    
2. Update the `getRelationshipMappings` method

    The `getRelationshipMappings` method defines the Parent-Child relationships for supported objects. If you want the chart you include objects other than the ones already defined (Account, Contact, Opportunity and Case), you'll have to add the definition in this method.

    Add a new entry to the relationshipMap in the `getRelationshipMappings` method, using the parent object's API name as the key and a list of ChildRelationship objects as the value. Each ChildRelationship is instantiated with the child object's API name and the API name of the lookup field on the child that references the parent.

    ```apex
    relationshipMap.put('Project__c', new List<ChildRelationship>{
    new ChildRelationship('Task__c', 'ProjectId__c')
    });
    ```

<div align="center">
	<img
	width="50">
	<h2>Example Customization</h2>
</div>


To support a custom object `Project__c` with a child object `Assignment__c` (via a lookup field `ProjectId__c`).

1. Add to `NAME_FIELD_MAP`
    
    ```apex
    'Project__c' => 'Project_Name__c',
    'Assignment__c' => 'Assignment_Name__c'
    ```
2. Add to `getRelationshipMappings`

    ```apex
    relationshipMap.put('Project__c', new List<ChildRelationship>{
    new ChildRelationship('Assignment__c', 'ProjectId__c')
    });
    ```

