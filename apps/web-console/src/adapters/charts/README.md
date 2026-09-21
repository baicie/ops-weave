# charts adapter

ECharts / Cytoscape 等可视化按页面需要再引入。图表实例必须在页面卸载时 dispose。
适配层只负责生命周期，不把查询权限或租户范围下放到图表库。
