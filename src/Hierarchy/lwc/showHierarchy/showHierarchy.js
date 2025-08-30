// hierarchyTreeChart.js
import { LightningElement, api, wire } from 'lwc';
import { ShowToastEvent } from 'lightning/platformShowToastEvent';
import { loadScript } from 'lightning/platformResourceLoader';
import D3 from '@salesforce/resourceUrl/d3js';
import { getObjectInfo } from 'lightning/uiObjectInfoApi';
import getHierarchyData from '@salesforce/apex/HierarchyController.getHierarchyData';

export default class ShowHierarchy extends LightningElement {
    @api recordId;
    @api objectApiName;
    @api width;
    @api height;
    @api nodeRadius;
    @api maxLevels;
    
    minNodeSpacing = 10; // Minimum vertical spacing between nodes
    hierarchyData = null;
    processedHierarchyData = null;
    isLoading = true;
    error = null;
    record;

    d3Initialized = false;
    svg = null;
    root = null;

    objectLabel;

    @wire(getObjectInfo, { objectApiName: '$objectApiName' })
    wiredObjectInfo({ error, data }) {
        if (data) {
            this.objectLabel = data.label;
        } else if (error) {
            console.error('Error retrieving object info:', error);
        }
    }

    async connectedCallback() {
        try {
            await this.loadD3();
            if (this.recordId) {
                await this.loadHierarchyData();
            }
        } catch (error) {
            console.error(error.message);
        }
    }

    async loadD3() {
        try {
            await loadScript(this, D3 + '/d3.v7.min.js');
            this.d3Initialized = true;
            console.log('D3 loaded successfully');
        } catch (error) {
            console.error(error.message);
        }
    }

    async loadHierarchyData() {
        if (!this.recordId) return;

        this.isLoading = true;
        try {
            const result = await getHierarchyData({
                recordId: this.recordId,
                currObj: this.objectApiName,
                maxLevels: this.maxLevels
            });
            this.hierarchyData = result;
        } catch (error) {
            console.error(error.message);
        } finally {
            this.isLoading = false;
        }
    }

    renderedCallback() {
        // Only render once per data load
        if (this.d3Initialized && this.hierarchyData && !this.svg) {
            this.renderChart();
        }
    }

    renderChart() {
        const container = this.template.querySelector('.chart-container');
        if (!container) return;

        // Clear previous chart
        container.innerHTML = '';

        // Calculate dynamic height based on number of nodes
        const visibleNodeCount = this.countVisibleNodes(this.root);        
        const calculatedHeight = this.height + visibleNodeCount * this.minNodeSpacing; 

        // Set up dimensions and margins
        const margin = { top: 20, right: 90, bottom: 30, left: 50 };
        this.width = this.width - margin.left - margin.right;

        // Create SVG with zoom and pan functionality
        this.svg = d3.select(container)
            .append('svg')
            .attr('width', this.width)
            .attr('height', this.height) // Keep original viewport height
            .call(d3.zoom()
                .scaleExtent([0.1, 3])
                .on('zoom', (event) => {
                    g.attr('transform', event.transform);
                }));

        const g = this.svg.append('g')
            .attr('transform', `translate(${margin.left},${margin.top})`);

        // Create tree layout with dynamic sizing
        d3.tree().size([calculatedHeight, this.width]);

        // Convert hierarchy data to d3 hierarchy
        this.root = d3.hierarchy(this.hierarchyData, d => d.children);
        this.root.x0 = this.height / 2;
        this.root.y0 = 0;

        // Collapse all nodes initially except root
        try {
            this.record = this.root.data.label;
            this.root.children.forEach(this.collapse);
        } catch {
            // Skip if the record has no child records
        }


        this.update(this.root, g);
    }

    //Method to count only visible (expanded) nodes
    countVisibleNodes(node) {
        if (!node) return 0;

        let count = 1; // Count the current node

        // Only count children if they are visible (node.children exists, not node._children)
        if (node.children) {
            node.children.forEach(child => {
                count += this.countVisibleNodes(child);
            });
        }

        return count;
    }

    collapse = (d) => {
        if (d.children) {
            d._children = d.children;
            d._children.forEach(this.collapse);
            d.children = null;
        }
    }

    update(source, g) {
        const visibleNodeCount = this.countVisibleNodes(this.root);
        // Update tree layout size if height changed significantly
        const calculatedHeight = this.height + visibleNodeCount * this.minNodeSpacing; 
        const treeLayout = d3.tree().size([calculatedHeight, this.width])
        const treeData = treeLayout(this.root);
        
        
        const nodes = treeData.descendants();
        const links = treeData.descendants().slice(1);

        // Spacing between parent and child node
        nodes.forEach(d => { d.y = d.depth * 180; });

        // Update nodes
        const node = g.selectAll('g.node')
            .data(nodes, function (d) {
                return d.data.id;
            });

        // Enter new nodes
        const nodeEnter = node.enter().append('g')
            .attr('class', 'node')
            .attr('transform', d => `translate(${source.y0},${source.x0})`)
            .on('click', (event, d) => this.click(event, d, g));

        // Add circles for nodes
        nodeEnter.append('circle')
            .attr('r', 1e-6)
            .style('fill', d => this.getNodeColor(d))
            .style('stroke', '#333')
            .style('stroke-width', '2px')
            .style('cursor', 'pointer');

        // Add labels
        nodeEnter.append('text')
            .attr('class', d => d.depth === 0 ? 'node-text root-node' : 'node-text')
            .attr('dy', '.35em')
            .attr('x', d => {
                if (d.depth === 0) {
                    return 15;
                } else {
                    return d.children || d._children ? -15 : 15;
                }
            })
            .text(d => {
                const label = d.data.label;
                const maxLength = d.depth === 0 ? 25 : 20;
                return label.length > maxLength ? label.substring(0, maxLength) + '...' : label;
            })
            .style('fill-opacity', 1e-6)
            .style('font-size', d => d.depth === 0 ? '14px' : '12px')
            .style('font-family', 'Salesforce Sans, Arial, sans-serif')
            .style('font-weight', d => d.depth === 0 ? '600' : '500')
            .style('transform', d => d.depth === 0 ? 'rotate(270deg)' : '')
            .style('cursor', 'pointer')
            .on('mouseover', (event, d) => {
                //
            })
            .on('mousemove', (event, d) => {
                //
            })
            .on('mouseout', (event, d) => {
                //
            })
            .on('click', (event, d) => {
                event.stopPropagation();
                this.handleTextClick(d);
            });

        const nodeUpdate = nodeEnter.merge(node);

        nodeUpdate
            .attr('transform', d => `translate(${d.y},${d.x})`);

        nodeUpdate.select('circle')
            .attr('r', this.nodeRadius)
            .style('fill', d => this.getNodeColor(d))


        nodeUpdate.select('text.node-text')
            .attr('x', d => {
                if (d.depth === 0) {
                    return 15;
                } else {
                    return d.children || d._children ? -15 : 15;
                }
            })
            .attr('text-anchor', d => {
                if (d.depth === 0) {
                    return 'start';
                } else {
                    return d.children || d._children ? 'end' : 'start';
                }
            })
            .style('fill-opacity', 1);

        nodeUpdate.select('text.node-type')
            .attr('x', d => {
                if (d.depth === 0) {
                    return 15;
                } else {
                    return d.children || d._children ? -15 : 15;
                }
            })
            .attr('text-anchor', d => {
                if (d.depth === 0) {
                    return 'start';
                } else {
                    return d.children || d._children ? 'end' : 'start';
                }
            })
            .style('fill-opacity', 1);

        // Remove exiting nodes
        const nodeExit = node.exit()
            .attr('transform', d => `translate(${source.y},${source.x})`)
            .remove();

        nodeExit.select('circle')
            .attr('r', 1e-6);

        nodeExit.select('text')
            .style('fill-opacity', 1e-6);

        // Update links - FIXED VERSION
        const link = g.selectAll('path.link')
            .data(links, d => d.data.id);

        // Enter new links (only for newly visible branches)
        const linkEnter = link.enter().insert('path', 'g')
            .attr('class', 'link')
            .style('fill', 'none')
            .style('stroke', '#ccc')
            .style('stroke-width', '2px')
            .attr('d', d => {
                const o = { x: source.x0, y: source.y0 };
                return this.diagonal(o, o);
            });

        // Update existing and new links
        const linkUpdate = linkEnter.merge(link);

        // Only animate links that are actually changing
        linkUpdate
            .attr('d', d => this.diagonal(d, d.parent));

        // Remove exiting links
        link.exit()
            .attr('d', d => {
                const o = { x: source.x, y: source.y };
                return this.diagonal(o, o);
            })
            .remove();

        nodes.forEach(d => {
            d.x0 = d.x;
            d.y0 = d.y;
        });
    }

    // Helper method to check if a node is a descendant of another node
    isDescendantOf(node, ancestor) {
        let current = node.parent;
        while (current) {
            if (current === ancestor) {
                return true;
            }
            current = current.parent;
        }
        return false;
    }

    // Get node color based on type and state
    getNodeColor(d) {
        if (d.depth === 0) {
            return '#FFD700';
        }
        if (d.data.isObjectGroup) {
            return '#9c88ff';
        }
        return d._children ? '#ff6b6b' : '#4ecdc4';
    }

    // Handle text click for navigation
    handleTextClick(d) {
        if (d.depth === 0 || d.data.isObjectGroup) {
            return; // Don't navigate for root node or object groups
        }
        window.open(`/${d.data.id}`, '_blank');
    }

    diagonal(s, d) {
        return `M ${s.y} ${s.x}
                C ${(s.y + d.y) / 2} ${s.x},
                  ${(s.y + d.y) / 2} ${d.x},
                  ${d.y} ${d.x}`;
    }

    click(event, d, g) {
        if (d.children) {
            d._children = d.children;
            d.children = null;
        } else {
            d.children = d._children;
            d._children = null;
        }
        this.update(d, g);
    }

    refreshChart() {
        // Reset chart so renderedCallback will run again
        this.hierarchyData = null;
        this.svg = null;
        this.loadHierarchyData();
    }

    // Method to export chart as SVG
    exportAsSVG() {
        if (!this.svg) return null;

        const svgElement = this.template.querySelector('svg');
        if (svgElement) {
            try{
                return new XMLSerializer().serializeToString(svgElement);
            } catch (error) {
                console.log(error);
            }
        }
        return null;
    }

    // Additional methods for the component
    get chartContainerStyle() {
        return `min-height: ${this.height}px; width: 100%; position: relative;`;
    }

    handleExport() {
        const svgContent = this.exportAsSVG();
        if (svgContent) {
            // Create blob and download
            const blob = new Blob([svgContent], { type: 'image/svg+xml' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `Hierarchy-${this.objectLabel}-${this.record}.svg`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);

            this.dispatchEvent(
                new ShowToastEvent({
                    title: 'Success',
                    message: 'Chart exported successfully',
                    variant: 'success'
                })
            );
        } else {
            this.dispatchEvent(
                new ShowToastEvent({
                    title: 'Error',
                    message: 'Unable to export chart',
                    variant: 'error'
                })
            );
        }
    }

    expandAll() {
        if (!this.root) return;

        this.expandAllNodes(this.root);
        const g = this.svg.select('g');
        this.update(this.root, g);
    }

    collapseAll() {
        if (!this.root) return;

        this.collapseAllNodes(this.root);
        const g = this.svg.select('g');
        this.update(this.root, g);
    }

    expandAllNodes(node) {
        if (node._children) {
            node.children = node._children;
            node._children = null;
        }

        if (node.children) {
            node.children.forEach(child => this.expandAllNodes(child));
        }
    }

    collapseAllNodes(node) {
        if (node.children) {
            node._children = node.children;
            node.children = null;
        }

        if (node._children) {
            node._children.forEach(child => this.collapseAllNodes(child));
        }
    }
}