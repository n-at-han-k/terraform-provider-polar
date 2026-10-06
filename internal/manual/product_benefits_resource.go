// SPDX-License-Identifier: MPL-2.0

// Package manual holds the resources the generator cannot derive from Polar's
// OpenAPI description, and is the only hand-written code in the provider.
//
// WHY IT IS A SEPARATE PACKAGE. bin/generate does `rm -rf internal/provider
// internal/client` before it regenerates -- a stale generated file compiles
// just as well as a fresh one -- so anything hand-written under those two
// directories is deleted by the next regeneration. This package is not, and the
// registration that reaches it lives in the provider TEMPLATE
// (generators/polar/resources/terraform-provider/provider.mustache) rather than
// in the generated provider.go, so a regeneration reproduces it instead of
// dropping it.
//
// IT DEPENDS ON client.Client AND client.APIError AND NOTHING ELSE from the
// generated side. Both come from the hand-written client template and are
// stable; the generated MODELS are not, so the shapes below are declared here.
package manual

import (
	"context"
	"errors"
	"fmt"
	"net/http"

	"encoding/json"

	"github.com/hashicorp/terraform-plugin-framework/diag"
	"github.com/hashicorp/terraform-plugin-framework/path"
	"github.com/hashicorp/terraform-plugin-framework/resource"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/planmodifier"
	"github.com/hashicorp/terraform-plugin-framework/resource/schema/stringplanmodifier"
	"github.com/hashicorp/terraform-plugin-framework/types"
	"github.com/hashicorp/terraform-plugin-log/tflog"

	"github.com/n-at-han-k/terraform-provider-polar/internal/client"
)

var _ resource.Resource = &ProductBenefitsResource{}
var _ resource.ResourceWithImportState = &ProductBenefitsResource{}

// NewProductBenefitsResource is registered from provider.mustache.
func NewProductBenefitsResource() resource.Resource {
	return &ProductBenefitsResource{}
}

type ProductBenefitsResource struct {
	client *client.Client
}

// ProductBenefitsModel is the whole of a product's benefit set, which is the
// only unit the API offers: POST /products/{id}/benefits REPLACES the list, so
// there is no resource for "one benefit on one product" that could be created
// and destroyed without reading and rewriting its siblings. One of these per
// product, holding every benefit that product grants.
type ProductBenefitsModel struct {
	ProductId  types.String `tfsdk:"product_id"`
	BenefitIds types.Set    `tfsdk:"benefit_ids"`
}

// productBenefitsUpdate is ProductBenefitsUpdate: the ids, under `benefits`.
type productBenefitsUpdate struct {
	Benefits []string `json:"benefits"`
}

// productWithBenefits is as much of a Product as the read needs. Declared here
// rather than taken from internal/client because that package is regenerated
// from whatever description Polar publishes next, and a field rename there
// should not silently stop this from refreshing.
type productWithBenefits struct {
	Benefits []struct {
		ID string `json:"id"`
	} `json:"benefits"`
}

func (r *ProductBenefitsResource) Metadata(_ context.Context, req resource.MetadataRequest, resp *resource.MetadataResponse) {
	resp.TypeName = req.ProviderTypeName + "_product_benefits"
}

func (r *ProductBenefitsResource) Schema(_ context.Context, _ resource.SchemaRequest, resp *resource.SchemaResponse) {
	resp.Schema = schema.Schema{
		Description: "Manages the complete set of benefits granted by a product. " +
			"Polar attaches benefits through POST /products/{id}/benefits, which replaces the list, " +
			"so this resource owns every benefit on the product it names -- declare one per product, " +
			"listing all of them.",
		Attributes: map[string]schema.Attribute{
			"product_id": schema.StringAttribute{
				Required: true,
				// The product IS the resource's identity; pointing this at
				// another product is a different attachment, not an edit of
				// this one. Without this, changing it would rewrite the new
				// product's benefits and leave the old product's as they were.
				PlanModifiers: []planmodifier.String{
					stringplanmodifier.RequiresReplace(),
				},
				Description: "The ID of the product whose benefits these are.",
			},
			"benefit_ids": schema.SetAttribute{
				Required:    true,
				ElementType: types.StringType,
				// A SET, NOT A LIST. Polar answers a product's benefits in its
				// own order, which is not the order they were sent, so a list
				// plans a diff on every refresh for an attachment that has not
				// changed.
				Description: "IDs of the benefits this product grants. Must all be in the same organization as the product.",
			},
		},
	}
}

func (r *ProductBenefitsResource) Configure(_ context.Context, req resource.ConfigureRequest, resp *resource.ConfigureResponse) {
	if req.ProviderData == nil {
		return
	}

	c, ok := req.ProviderData.(*client.Client)
	if !ok {
		resp.Diagnostics.AddError(
			"Unexpected Resource Configure Type",
			fmt.Sprintf("Expected *client.Client, got: %T.", req.ProviderData),
		)
		return
	}

	r.client = c
}

func (r *ProductBenefitsResource) Create(ctx context.Context, req resource.CreateRequest, resp *resource.CreateResponse) {
	var plan ProductBenefitsModel

	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}

	resp.Diagnostics.Append(r.attach(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}

	tflog.Trace(ctx, "attached product benefits")
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

// Read refreshes from the product itself: the attachment has no endpoint of its
// own, so what the product says it grants IS the state of this resource.
func (r *ProductBenefitsResource) Read(ctx context.Context, req resource.ReadRequest, resp *resource.ReadResponse) {
	var state ProductBenefitsModel

	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}

	// A row with no product cannot be refreshed: an empty id would ask the
	// COLLECTION endpoint, which answers 200 and a list of products, and the
	// row would look healthy forever.
	if state.ProductId.ValueString() == "" {
		resp.State.RemoveResource(ctx)
		return
	}

	product, err := r.readProduct(ctx, state.ProductId.ValueString())
	if err != nil {
		// GONE IS NOT BROKEN: the product this row describes no longer exists,
		// so the attachment cannot either. Dropping the row lets the plan
		// decide; raising fails every plan in the configuration.
		var apiErr *client.APIError
		if errors.As(err, &apiErr) && apiErr.StatusCode == http.StatusNotFound {
			resp.State.RemoveResource(ctx)
			return
		}

		resp.Diagnostics.AddError("Error reading product benefits", err.Error())
		return
	}

	ids, diags := benefitIds(ctx, product)
	if diags.HasError() {
		resp.Diagnostics.Append(diags...)
		return
	}
	state.BenefitIds = ids

	resp.Diagnostics.Append(resp.State.Set(ctx, &state)...)
}

func (r *ProductBenefitsResource) Update(ctx context.Context, req resource.UpdateRequest, resp *resource.UpdateResponse) {
	var plan ProductBenefitsModel

	resp.Diagnostics.Append(req.Plan.Get(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}

	// The same call as a create, because the API has one: it replaces the set
	// whether or not there was one before.
	resp.Diagnostics.Append(r.attach(ctx, &plan)...)
	if resp.Diagnostics.HasError() {
		return
	}

	tflog.Trace(ctx, "updated product benefits")
	resp.Diagnostics.Append(resp.State.Set(ctx, &plan)...)
}

// Delete DETACHES EVERY BENEFIT from the product, because that is what
// destroying "the product's benefit set" means and there is nothing narrower to
// ask for. A product keeps existing with no benefits attached; customers who
// already hold a grant keep it until Polar revokes it on their next cycle.
func (r *ProductBenefitsResource) Delete(ctx context.Context, req resource.DeleteRequest, resp *resource.DeleteResponse) {
	var state ProductBenefitsModel

	resp.Diagnostics.Append(req.State.Get(ctx, &state)...)
	if resp.Diagnostics.HasError() {
		return
	}

	if state.ProductId.ValueString() == "" {
		return
	}

	_, err := r.client.DoRequest(ctx, "POST",
		fmt.Sprintf("/products/%v/benefits", state.ProductId.ValueString()),
		productBenefitsUpdate{Benefits: []string{}},
	)
	if err != nil {
		// A product that is already gone has no benefits to detach, and failing
		// here would leave a state row for something that does not exist.
		var apiErr *client.APIError
		if errors.As(err, &apiErr) && apiErr.StatusCode == http.StatusNotFound {
			return
		}

		resp.Diagnostics.AddError("Error detaching product benefits", err.Error())
	}
}

// ImportState takes the PRODUCT id, which is this resource's identity. The
// benefit ids come from the read that follows.
func (r *ProductBenefitsResource) ImportState(ctx context.Context, req resource.ImportStateRequest, resp *resource.ImportStateResponse) {
	resource.ImportStatePassthroughID(ctx, path.Root("product_id"), req, resp)
}

// attach sends the set and reads back what the product now grants. The POST
// answers a Product, but it is read back anyway: taking an answer as state is
// how a resource ends up "produced inconsistent result after apply" the day the
// answer stops being the resource.
func (r *ProductBenefitsResource) attach(ctx context.Context, plan *ProductBenefitsModel) diag.Diagnostics {
	var diags diag.Diagnostics

	var ids []string
	diags.Append(plan.BenefitIds.ElementsAs(ctx, &ids, false)...)
	if diags.HasError() {
		return diags
	}

	productId := plan.ProductId.ValueString()

	if _, err := r.client.DoRequest(ctx, "POST",
		fmt.Sprintf("/products/%v/benefits", productId),
		productBenefitsUpdate{Benefits: ids},
	); err != nil {
		diags.AddError("Error attaching product benefits", err.Error())
		return diags
	}

	product, err := r.readProduct(ctx, productId)
	if err != nil {
		diags.AddError("Error reading back the product's benefits", err.Error())
		return diags
	}

	read, readDiags := benefitIds(ctx, product)
	diags.Append(readDiags...)
	if diags.HasError() {
		return diags
	}
	plan.BenefitIds = read

	return diags
}

func (r *ProductBenefitsResource) readProduct(ctx context.Context, productId string) (*productWithBenefits, error) {
	body, err := r.client.DoRequest(ctx, "GET", fmt.Sprintf("/products/%v", productId), nil)
	if err != nil {
		return nil, err
	}

	var product productWithBenefits
	if err := json.Unmarshal(body, &product); err != nil {
		return nil, fmt.Errorf("parsing product %s: %w", productId, err)
	}

	return &product, nil
}

func benefitIds(ctx context.Context, product *productWithBenefits) (types.Set, diag.Diagnostics) {
	ids := make([]string, 0, len(product.Benefits))
	for _, benefit := range product.Benefits {
		ids = append(ids, benefit.ID)
	}

	return types.SetValueFrom(ctx, types.StringType, ids)
}
