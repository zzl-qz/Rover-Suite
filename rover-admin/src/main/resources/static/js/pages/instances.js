/** 实例管理页。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.instances = {
    data() {
        return {
            instances: [],
            instancesError: null,
        };
    },

    computed: {},

    methods: {
        async fetchInstances() {
            this.instancesError = null;
            try {
                this.instances = await RoverAdminApi.api('/api/instances');
            } catch (e) {
                this.instancesError = e.message;
            }
        },
    },
};
